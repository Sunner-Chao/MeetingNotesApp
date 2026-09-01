import { ArrowLeft, CheckCircle2, Compass, FileText, ImageOff, MapPin, MessageCircle, Search, Send, Share2, Undo2, UserRound, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { fetchMyCommunityPosts, fetchPublicCommunityComments, fetchPublicCommunityPost, fetchPublicCommunityPosts, publishMyCommunityPost, withdrawMyCommunityPost } from "../lib/api";
import type { AuthSession, Meeting, OwnerCommunityPost, PublicCommunityComment, PublicCommunityPost, RuntimeConfig } from "../types";
import { CommunityComposer } from "./CommunityComposer";

interface CommunityScreenProps {
  config: RuntimeConfig;
  session: AuthSession;
  meetings: Meeting[];
  onNotify: (message: string, kind?: "success" | "error") => void;
}

function mediaUrl(path: string): string {
  if (!path || /^https?:\/\//i.test(path)) return path;
  return new URL(path.replace(/^\//, ""), window.location.origin + "/").toString();
}

function updateCommunityDeepLink(postId?: string) {
  const url = new URL(window.location.href);
  if (postId) url.searchParams.set("community", postId);
  else url.searchParams.delete("community");
  window.history.replaceState({}, "", `${url.pathname}${url.search}${url.hash}`);
}

function ownerStatusLabel(post: OwnerCommunityPost): string {
  if (post.status === "withdrawn") return "已撤回";
  if (post.status === "private_draft") return "草稿";
  if (post.review.status === "approved") return "已公开";
  if (post.review.status === "rejected") return "需修改";
  return "审核中";
}

export function CommunityScreen({ config, session, meetings, onNotify }: CommunityScreenProps) {
  const [view, setView] = useState<"public" | "mine">("public");
  const [posts, setPosts] = useState<PublicCommunityPost[]>([]);
  const [myPosts, setMyPosts] = useState<OwnerCommunityPost[]>([]);
  const [query, setQuery] = useState("");
  const [destination, setDestination] = useState("");
  const [sort, setSort] = useState<"curated" | "latest">("curated");
  const [loading, setLoading] = useState(true);
  const [selectedPost, setSelectedPost] = useState<PublicCommunityPost>();
  const [selectedOwnerPost, setSelectedOwnerPost] = useState<OwnerCommunityPost>();
  const [ownerActionBusy, setOwnerActionBusy] = useState(false);
  const [comments, setComments] = useState<PublicCommunityComment[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [composerOpen, setComposerOpen] = useState(false);
  const [composerMeetingId, setComposerMeetingId] = useState<string>();
  const [composerPost, setComposerPost] = useState<OwnerCommunityPost>();

  const destinations = useMemo(
    () => [...new Set(posts.map((post) => post.destination).filter(Boolean))],
    [posts]
  );
  const visiblePosts = useMemo(() => {
    const items = [...posts];
    if (sort === "latest") items.sort((left, right) => right.published_at - left.published_at);
    else items.sort((left, right) => right.like_count - left.like_count || right.published_at - left.published_at);
    return items;
  }, [posts, sort]);

  useEffect(() => {
    let active = true;
    const timer = window.setTimeout(() => {
      setLoading(true);
      if (view === "mine") {
        void fetchMyCommunityPosts(config, session)
          .then((result) => { if (active) setMyPosts(result.items); })
          .catch((error) => { if (active) onNotify(error instanceof Error ? error.message : "我的内容加载失败", "error"); })
          .finally(() => { if (active) setLoading(false); });
        return;
      }
      void fetchPublicCommunityPosts(config, { query, destination, hasMedia: false })
        .then((result) => { if (active) setPosts(result.items); })
        .catch((error) => { if (active) onNotify(error instanceof Error ? error.message : "社区内容加载失败", "error"); })
        .finally(() => { if (active) setLoading(false); });
    }, 220);
    return () => { active = false; window.clearTimeout(timer); };
  }, [config, destination, onNotify, query, session, view]);

  const openPost = async (postId: string) => {
    setDetailLoading(true);
    updateCommunityDeepLink(postId);
    try {
      const [post, commentPage] = await Promise.all([
        fetchPublicCommunityPost(config, postId),
        fetchPublicCommunityComments(config, postId)
      ]);
      setSelectedPost(post);
      setComments(commentPage.items || []);
    } catch (error) {
      updateCommunityDeepLink();
      onNotify(error instanceof Error ? error.message : "文章加载失败", "error");
    } finally {
      setDetailLoading(false);
    }
  };

  useEffect(() => {
    const postId = new URLSearchParams(window.location.search).get("community")?.trim();
    if (postId) void openPost(postId);
  }, []);

  const closePost = () => {
    setSelectedPost(undefined);
    setComments([]);
    updateCommunityDeepLink();
  };

  const sharePost = async (post: PublicCommunityPost) => {
    const url = new URL(window.location.href);
    url.searchParams.set("community", post.id);
    const payload = { title: post.title, text: `${post.title}\n${post.destination || "智悟本社区"}`, url: url.toString() };
    if (navigator.share) await navigator.share(payload);
    else {
      await navigator.clipboard.writeText(`${payload.text}\n${payload.url}`);
      onNotify("分享链接已复制", "success");
    }
  };

  const runOwnerAction = async (action: "publish" | "withdraw") => {
    if (!selectedOwnerPost || ownerActionBusy) return;
    setOwnerActionBusy(true);
    try {
      const updated = action === "publish"
        ? await publishMyCommunityPost(config, session, selectedOwnerPost.id)
        : await withdrawMyCommunityPost(config, session, selectedOwnerPost.id);
      setMyPosts((current) => current.map((post) => post.id === selectedOwnerPost.id ? { ...post, ...updated } : post));
      setSelectedOwnerPost((current) => current ? { ...current, ...updated } : current);
      onNotify(action === "publish" ? "内容已提交审核" : "内容已撤回", "success");
    } catch (error) {
      onNotify(error instanceof Error ? error.message : "社区内容操作失败", "error");
    } finally {
      setOwnerActionBusy(false);
    }
  };

  const refreshMine = async () => {
    const result = await fetchMyCommunityPosts(config, session);
    setMyPosts(result.items);
  };

  return (
    <div className="screen community-screen">
      <header className="screen-header community-header">
        <div><span className="eyebrow">真实研学与成长记录</span><h1>社区</h1></div>
        <div className="community-header-actions"><div className="segmented compact-segmented" aria-label="社区内容范围">
          <button className={view === "public" ? "active" : ""} onClick={() => setView("public")}><Compass />公开</button>
          <button className={view === "mine" ? "active" : ""} onClick={() => setView("mine")}><UserRound />我的</button>
        </div><button className="primary-button compact-button" onClick={() => { setComposerMeetingId(undefined); setComposerPost(undefined); setComposerOpen(true); }}><Send />创建内容</button></div>
      </header>

      {view === "mine" && <div className="community-create-banner"><div><strong>把一次记录整理成可分享的研学内容</strong><span>选择已有会议，补充地点与标签，完成检查后提交审核。</span></div><button className="secondary-button compact-button" onClick={() => { setComposerMeetingId(meetings[0]?.id); setComposerPost(undefined); setComposerOpen(true); }} disabled={meetings.length === 0}><FileText />从会议创建</button></div>}

      {view === "public" && <>
        <div className="community-tools">
          <label className="search-field"><Search /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索目的地、主题或正文" /></label>
          <select value={destination} onChange={(event) => setDestination(event.target.value)} aria-label="筛选目的地">
            <option value="">全部目的地</option>
            {destinations.map((item) => <option value={item} key={item}>{item}</option>)}
          </select>
        </div>
        <div className="community-sort-row" aria-label="公开内容排序">
          <span>内容排序</span>
          <div className="segmented compact-segmented">
            <button className={sort === "curated" ? "active" : ""} onClick={() => setSort("curated")}>推荐</button>
            <button className={sort === "latest" ? "active" : ""} onClick={() => setSort("latest")}>最新</button>
          </div>
        </div>
      </>}

      {loading ? <div className="community-loading">正在加载{view === "mine" ? "我的内容" : "社区内容"}</div> : view === "mine" ? (
        myPosts.length === 0 ? <div className="empty-state community-empty-rich"><FileText /><strong>还没有社区内容</strong><small>在 Android 端完成旅程整理并发布后，会在这里看到草稿和审核状态。</small></div> : (
          <section className="community-owner-list" aria-label="我的社区内容">
            {myPosts.map((post) => <button className="community-owner-row" type="button" key={post.id} onClick={() => setSelectedOwnerPost(post)}>
              <span className="community-owner-icon"><FileText /></span>
              <span className="community-owner-copy"><strong>{post.title}</strong><small>{post.content.replace(/\s+/g, " ").slice(0, 120)}</small><em>更新于 {new Date(post.updated_at).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" })}</em></span>
              <span className={`community-owner-status status-${post.review.status}`}>{ownerStatusLabel(post)}</span>
            </button>)}
          </section>
        )
      ) : visiblePosts.length === 0 ? (
        <div className="empty-state">暂时没有匹配的公开内容</div>
      ) : (
        <section className="community-grid" aria-label="社区文章">
          {visiblePosts.map((post) => (
            <article className="community-card" key={post.id}>
              <button className="community-card-main" onClick={() => void openPost(post.id)}>
                {post.media[0]?.thumbnail_url ? <img src={mediaUrl(post.media[0].thumbnail_url)} alt="" loading="lazy" /> : <span className="community-media-empty"><ImageOff /></span>}
                <span className="community-card-copy">
                  <strong>{post.title}</strong>
                  <small>{post.content.replace(/\s+/g, " ").slice(0, 86)}</small>
                  <span className="community-card-meta">
                    <span>{post.destination && <><MapPin />{post.destination}</>}</span>
                    <span><MessageCircle />{post.comment_count}</span>
                  </span>
                </span>
              </button>
              <footer><span>{post.author_label || "研学同行者"}</span><button className="icon-button" title="分享文章" onClick={() => void sharePost(post).catch(() => undefined)}><Share2 /></button></footer>
            </article>
          ))}
        </section>
      )}

      {detailLoading && <div className="community-detail-backdrop"><div className="community-detail-loading">正在打开文章</div></div>}
      {selectedPost && (
        <div className="community-detail-backdrop" onMouseDown={closePost}>
          <article className="community-detail" role="dialog" aria-modal="true" aria-labelledby="community-detail-title" onMouseDown={(event) => event.stopPropagation()}>
            <header><button className="icon-button" title="返回社区" onClick={closePost}><ArrowLeft /></button><span>{selectedPost.destination || "社区文章"}</span><button className="icon-button" title="分享文章" onClick={() => void sharePost(selectedPost).catch(() => undefined)}><Share2 /></button></header>
            <div className="community-detail-scroll">
              {selectedPost.media.length > 0 && <div className="community-detail-media">{selectedPost.media.map((media) => <img src={mediaUrl(media.content_url)} alt="" key={media.id} />)}</div>}
              <div className="community-detail-copy">
                <h2 id="community-detail-title">{selectedPost.title}</h2>
                <div className="community-detail-meta"><span>{selectedPost.author_label}</span><span>{new Date(selectedPost.published_at * 1000).toLocaleDateString("zh-CN", { timeZone: "Asia/Shanghai" })}</span></div>
                <p>{selectedPost.content}</p>
                {selectedPost.tags.length > 0 && <div className="community-tags">{selectedPost.tags.map((tag) => <span key={tag}>#{tag}</span>)}</div>}
                <section className="community-comments"><h3>评论 {selectedPost.comment_count}</h3>{comments.length === 0 ? <small>暂无评论</small> : comments.map((comment) => <div key={comment.id}><strong>{comment.author_label}</strong><p>{comment.content}</p><small>{new Date(comment.created_at * 1000).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" })}</small></div>)}</section>
              </div>
            </div>
          </article>
        </div>
      )}
      {selectedOwnerPost && (
        <div className="community-detail-backdrop" onMouseDown={() => setSelectedOwnerPost(undefined)}>
          <article className="community-detail community-owner-detail" role="dialog" aria-modal="true" aria-labelledby="community-owner-detail-title" onMouseDown={(event) => event.stopPropagation()}>
            <header><button className="icon-button" title="关闭" onClick={() => setSelectedOwnerPost(undefined)}><X /></button><span>我的内容</span><span /></header>
            <div className="community-detail-scroll">
              <div className="community-detail-copy">
                <div className="community-owner-detail-status"><span className={`community-owner-status status-${selectedOwnerPost.review.status}`}><CheckCircle2 />{ownerStatusLabel(selectedOwnerPost)}</span><small>更新于 {new Date(selectedOwnerPost.updated_at).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" })}</small></div>
                <h2 id="community-owner-detail-title">{selectedOwnerPost.title}</h2>
                <p>{selectedOwnerPost.content}</p>
                {selectedOwnerPost.review.reason && <aside className="community-review-note"><strong>审核说明</strong><p>{selectedOwnerPost.review.reason}</p></aside>}
              </div>
            </div>
            <footer className="community-owner-detail-actions">
              {selectedOwnerPost.status === "private_draft" && <button className="secondary-button" disabled={ownerActionBusy} onClick={() => { setComposerPost(selectedOwnerPost); setComposerMeetingId(undefined); setSelectedOwnerPost(undefined); setComposerOpen(true); }}>编辑草稿</button>}
              {selectedOwnerPost.status === "private_draft" && <button className="primary-button" disabled={ownerActionBusy} onClick={() => void runOwnerAction("publish")}><Send />提交审核</button>}
              {selectedOwnerPost.status !== "withdrawn" && selectedOwnerPost.status !== "private_draft" && <button className="secondary-button" disabled={ownerActionBusy} onClick={() => void runOwnerAction("withdraw")}><Undo2 />撤回内容</button>}
            </footer>
          </article>
        </div>
      )}
      {composerOpen && <CommunityComposer config={config} session={session} meetings={meetings} initialMeetingId={composerMeetingId} initialPost={composerPost} onClose={() => { setComposerOpen(false); setComposerPost(undefined); }} onSaved={(post) => { setMyPosts((current) => [post, ...current.filter((item) => item.id !== post.id)]); setView("mine"); }} onNotify={async (message, kind) => { onNotify(message, kind); if (message === "内容已提交审核" || message === "草稿已保存") { await refreshMine().catch(() => undefined); } }} />}
    </div>
  );
}
