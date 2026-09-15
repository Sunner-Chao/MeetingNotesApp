// Incremental Zipformer benchmark. Input: little-endian sample count + PCM16.
// A zero sample count ends input. The model stays resident for the whole stream.
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <iostream>
#include <string>
#include <vector>
#include <algorithm>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif
#include "nlohmann/json.hpp"
#include "sherpa-ncnn/csrc/recognizer.h"

using Clock = std::chrono::steady_clock;
using Json = nlohmann::json;
static double Ms(Clock::time_point a, Clock::time_point b) {
  return std::chrono::duration<double, std::milli>(b-a).count();
}
static void Emit(const Json &j) { std::cout << j.dump() << std::endl; }
int main(int argc, char **argv) {
  if (argc != 3) { std::cerr << "usage: ncnn-stream-probe MODEL_DIR THREADS\n"; return 2; }
#ifdef _WIN32
  _setmode(_fileno(stdin), _O_BINARY);
#endif
  const std::string dir = argv[1];
  const int threads = std::atoi(argv[2]);
  if (threads < 1 || threads > 16) return 2;
  sherpa_ncnn::RecognizerConfig cfg;
  cfg.model_config.tokens = dir + "/tokens.txt";
  cfg.model_config.encoder_param = dir + "/encoder_jit_trace-pnnx.ncnn.param";
  cfg.model_config.encoder_bin = dir + "/encoder_jit_trace-pnnx.ncnn.bin";
  cfg.model_config.decoder_param = dir + "/decoder_jit_trace-pnnx.ncnn.param";
  cfg.model_config.decoder_bin = dir + "/decoder_jit_trace-pnnx.ncnn.bin";
  cfg.model_config.joiner_param = dir + "/joiner_jit_trace-pnnx.ncnn.param";
  cfg.model_config.joiner_bin = dir + "/joiner_jit_trace-pnnx.ncnn.bin";
  cfg.model_config.encoder_opt.num_threads = threads;
  cfg.model_config.decoder_opt.num_threads = threads;
  cfg.model_config.joiner_opt.num_threads = threads;
  cfg.feat_config.sampling_rate = 16000;
  cfg.feat_config.feature_dim = 80;
  cfg.decoder_config.method = "greedy_search";
  const auto load_start = Clock::now();
  sherpa_ncnn::Recognizer recognizer(cfg);
  auto stream = recognizer.CreateStream();
  Emit({{"type","ready"},{"model","zipformer-zh-14M"},{"backend","ncnn-cpu"},
        {"threads",threads},{"load_ms",Ms(load_start,Clock::now())}});
  int64_t samples_seen=0;
  int steps=0, updates=0;
  double decode_total=0, decode_max=0, first_ms=-1;
  bool bad=false, started=false;
  Clock::time_point start;
  std::string previous;
  auto decode = [&]() {
    while (recognizer.IsReady(stream.get())) {
      auto t=Clock::now();
      recognizer.DecodeStream(stream.get());
      double cost=Ms(t,Clock::now());
      decode_total+=cost; decode_max=std::max(decode_max,cost); ++steps;
    }
    const auto result=recognizer.GetResult(stream.get());
    if (!result.text.empty() && result.text!=previous) {
      double elapsed=Ms(start,Clock::now());
      if (first_ms<0) first_ms=elapsed;
      ++updates; previous=result.text;
      Emit({{"type","preview"},{"end_ms",samples_seen/16.0},
            {"server_elapsed_ms",elapsed},{"text",result.text}});
    }
  };
  while (true) {
    uint32_t count=0;
    if (std::fread(&count,4,1,stdin)!=1) { bad=true; break; }
    if (count==0) break;
    if (count>16000) { bad=true; break; }
    std::vector<int16_t> pcm(count);
    if (std::fread(pcm.data(),2,count,stdin)!=count) { bad=true; break; }
    if (!started) { started=true; start=Clock::now(); }
    std::vector<float> audio(count);
    for (uint32_t i=0;i<count;++i) audio[i]=pcm[i]/32768.f;
    samples_seen+=count;
    stream->AcceptWaveform(16000,audio.data(),count);
    decode();
  }
  if (started && !bad) {
    std::vector<float> tail(4800);
    stream->AcceptWaveform(16000,tail.data(),tail.size());
    decode();
    stream->Finalize();
  }
  Emit({{"type","summary"},{"audio_ms",samples_seen/16.0},
        {"first_partial_server_ms",first_ms},{"decode_steps",steps},{"updates",updates},
        {"decode_total_ms",decode_total},{"decode_mean_ms",steps?decode_total/steps:0},
        {"decode_max_ms",decode_max},{"final_text",previous},
        {"elapsed_server_ms",started?Ms(start,Clock::now()):0},
        {"failures",0},{"bad_input",bad}});
  return bad?1:0;
}
