// Experimental resident Whisper benchmark. stdin: uint32 count + PCM16LE samples.
// stdout: JSONL ready/preview/summary events. This is not a production endpoint.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#define _WIN32_WINNT 0x0601
#include "whisper.h"
#include <windows.h>
#include <psapi.h>
#include <io.h>
#include <fcntl.h>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

using Clock=std::chrono::steady_clock;
static double ms(Clock::duration value){return std::chrono::duration<double,std::milli>(value).count();}
static std::string quote(const std::string& value) {
    std::string out="\"";
    for(unsigned char c:value) {
        if(c=='"'||c=='\\'){out+='\\';out+=c;}
        else if(c<32){char esc[7];snprintf(esc,sizeof(esc),"\\u%04x",c);out+=esc;}
        else out+=c;
    }
    return out+'"';
}
int main(int argc,char** argv) {
    if(argc<7){fprintf(stderr,"usage: probe model step_ms window_ms threads audio_ctx prompt\n");return 2;}
    const int step_ms=atoi(argv[2]),window_ms=atoi(argv[3]),threads=atoi(argv[4]),audio_ctx=atoi(argv[5]);
    if(step_ms<500||window_ms<step_ms||window_ms>28000||threads<1)return 2;
    _setmode(_fileno(stdin),_O_BINARY);
    const auto loading=Clock::now();
    auto cp=whisper_context_default_params();cp.use_gpu=true;
    auto* ctx=whisper_init_from_file_with_params(argv[1],cp);
    if(!ctx)return 3;
    printf("{\"type\":\"ready\",\"load_ms\":%.2f,\"step_ms\":%d,\"window_ms\":%d,\"audio_ctx\":%d}\n",ms(Clock::now()-loading),step_ms,window_ms,audio_ctx);fflush(stdout);
    std::mutex mutex;std::condition_variable changed;std::vector<float> received;
    bool eof=false,bad_input=false;Clock::time_point started;
    std::thread reader([&]{
        while(true){
            uint32_t count=0;
            if(fread(&count,4,1,stdin)!=1){bad_input=true;break;}
            if(!count)break;
            if(count>16000){bad_input=true;break;}
            std::vector<int16_t> pcm(count);
            if(fread(pcm.data(),2,count,stdin)!=count){bad_input=true;break;}
            {std::lock_guard<std::mutex> lock(mutex);
                if(received.empty())started=Clock::now();
                if(received.size()+count>size_t(16000)*1800){bad_input=true;break;}
                for(auto sample:pcm)received.push_back(sample/32768.f);
            }changed.notify_one();
        }
        {std::lock_guard<std::mutex> lock(mutex);eof=true;}changed.notify_one();
    });
    const size_t step=size_t(step_ms)*16,window=size_t(window_ms)*16;
    size_t consumed=0;int calls=0,failures=0;double total_inference=0,max_delay=0;
    while(true){
        std::vector<float> samples;size_t end=0,begin=0;
        {
            std::unique_lock<std::mutex> lock(mutex);
            changed.wait(lock,[&]{return eof||received.size()>=consumed+step;});
            if(eof&&consumed==received.size())break;
            end=std::min(received.size(),consumed+step);begin=end>window?end-window:0;
            samples.assign(received.begin()+begin,received.begin()+end);
        }
        auto p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.n_threads=threads;p.language="zh";p.translate=false;
        p.print_progress=false;p.print_realtime=false;p.print_timestamps=false;p.print_special=false;
        p.single_segment=true;p.no_timestamps=true;p.no_context=true;p.max_tokens=128;
        p.temperature_inc=0;p.greedy.best_of=1;p.initial_prompt=argv[6][0]&&std::string(argv[6])!="none"?argv[6]:nullptr;
        p.audio_ctx=audio_ctx<0?std::min(1500,int((samples.size()/320+50+127)/128*128)):audio_ctx;
        const auto call_started=Clock::now();
        const int status=whisper_full(ctx,p,samples.data(),int(samples.size()));
        const double inference=ms(Clock::now()-call_started);total_inference+=inference;
        std::string text;
        if(status)failures++;
        else for(int i=0;i<whisper_full_n_segments(ctx);i++)text+=whisper_full_get_segment_text(ctx,i);
        size_t current;
        {std::lock_guard<std::mutex> lock(mutex);current=received.size();}
        const double wall=ms(Clock::now()-started),delay=wall-end/16.0;
        max_delay=std::max(max_delay,delay);consumed=end;calls++;
        PROCESS_MEMORY_COUNTERS memory={};GetProcessMemoryInfo(GetCurrentProcess(),&memory,sizeof(memory));
        printf("{\"type\":\"preview\",\"call\":%d,\"start_ms\":%.2f,\"end_ms\":%.2f,\"wall_ms\":%.2f,\"inference_ms\":%.2f,\"delay_ms\":%.2f,\"backlog_ms\":%.2f,\"rss_mb\":%.2f,\"audio_ctx\":%d,\"status\":%d,\"text\":%s}\n",calls,begin/16.0,end/16.0,wall,inference,delay,(current-end)/16.0,memory.WorkingSetSize/1048576.0,p.audio_ctx,status,quote(text).c_str());fflush(stdout);
    }
    reader.join();
    printf("{\"type\":\"summary\",\"calls\":%d,\"failures\":%d,\"bad_input\":%s,\"audio_ms\":%.2f,\"inference_ms\":%.2f,\"wall_ms\":%.2f,\"max_delay_ms\":%.2f}\n",calls,failures,bad_input?"true":"false",consumed/16.0,total_inference,consumed?ms(Clock::now()-started):0,max_delay);fflush(stdout);
    whisper_free(ctx);return failures||bad_input?4:0;
}
