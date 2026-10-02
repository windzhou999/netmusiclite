package com.ncm.watch.data

import androidx.compose.runtime.Immutable

/** 全局数据模型：全部 @Immutable（流畅度 R3）——
 *  数据类字段不可变，标注后 Compose 视为稳定类型，
 *  父级重组时列表卡片可跳过重组（滚动/状态变化时的关键降载） */

@Immutable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val album: String,
    val albumId: Long,
    val durationMs: Int,
    val coverUrl: String?,
    /**
     * 别名 / 译名集合（跨语言搜索用）。
     *
     * 来源是网易接口自带的三个数组，按语言分列：
     *  · `tns`   —— 官方译名（「夜に駆ける」→「向夜晚奔去」）
     *  · `alia`  —— 官方别名（多为「TV动画《…》片头曲」这类说明，也含旧译名）
     *  · `alias` / `transNames` —— 老接口 `/api/search/get/web` 的等价字段（别名 / 译名）
     *
     * 有 id 的歌（收藏、每日推荐、歌单、专辑、搜索结果）随响应一起带回来；
     * 本地文件没有 id，由 [SongAliasStore] 反查后回填（见该类 KDoc）。
     * 空集合表示「尚未解析」，不代表没有别名。
     */
    val aliases: List<String> = emptyList(),
)

@Immutable
data class ArtistItem(val id: Long, val name: String, val avatarUrl: String?)

@Immutable
data class AlbumItem(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val artist: String,
    val publishTime: Long,
    val songCount: Int,
)

@Immutable
data class PlaylistItem(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val playCount: Long,
    val isMine: Boolean,
)

@Immutable
data class FriendInfo(val id: Long, val name: String, val avatarUrl: String?)

/** 私信单条（来自 /api/msg/private/history） */
@Immutable
data class ChatMessage(
    val id: Long,
    val text: String,
    val timeMs: Long,
    val fromMe: Boolean,
    /** 非空 = 歌曲卡片消息：聊天页渲染成可点封面卡，点击直接播放 */
    val song: Song? = null,
)

/** 私信会话（来自 /api/msg/private/users：msgs 会话数组 + users 资料数组合并而来） */
@Immutable
data class ChatSession(
    val userId: Long,
    val name: String,
    val avatarUrl: String?,
    val lastText: String,
    val timeMs: Long,
    val unread: Int,
)

@Immutable
data class LyricLine(val timeMs: Long, val text: String)

/** 用户资料（他人主页头部用） */
data class UserDetail(val nickname: String, val avatarUrl: String?, val listenSongs: Long, val level: Int)

/**
 * 歌曲百科制作人员（作词/作曲/编曲…）。
 * ⚠ 2026-10-01 起恒为空：唯一来源 `/api/song/wiki/summary` 实测 404，
 * 而官方现行的 `block/page` 里没有「制作人员」对应的 creativeType（多样本实测只有
 * songTag / songBizTag / language / bpm / songAward / entertainment / sheet / songComment）。
 * 字段先留着（UI 已不再展示），等找到真实来源再接。
 */
data class CrewMember(val role: String, val name: String)

/**
 * 歌曲百科聚合（字段可能缺失，UI 按空值隐藏对应卡片）。
 *
 * 口径分两层，**优先“回忆坐标”层**（官方百科页同源）：
 *  ① 回忆坐标层（2026-10-01 新增）＝ 官方 RN 百科页 `/api/song/play/about/block/page`
 *     里 `MUSIC_MEMORY_MULTI_TWO_GRID` 区块的两个 resource：
 *       · `FIRST_LISTEN` → resourceExt.musicFirstListenDto ——「第一次听」
 *       · `TOTAL_PLAY`   → resourceExt.musicTotalPlayDto  ——「累计听过」
 *     这一层要登录、且账号对这首歌有收听记录才有值（未登录服务端直接给空 creatives）。
 *  ② 听歌排行层（旧口径）＝ `/v1/play/record` allData（top1000 榜），见 [playCount]/[firstPlayMs]。
 *     榜外的歌官方也不给累计次数，因此它是回忆坐标缺失时的兜底，不是等价替换。
 */
data class SongBaikeData(
    val firstPlayMs: Long?,
    val playCount: Int?,
    val tags: List<String>,
    val crew: List<CrewMember>,
    val publishTime: Long?,
    val similar: List<Song>,
    /** 回忆坐标区块标题（官方默认「回忆坐标」） */
    val memoryTitle: String? = null,
    /** 累计听过次数（官方 musicTotalPlayDto.playCount；官方 UI 对 >999 截断显示「999+」） */
    val listenCount: Int? = null,
    /** 累计听过附注（旧版 text / 新版 desc，官方原文照搬） */
    val listenHint: String? = null,
    /** 第一次听的主文案：旧版「2023年的夏天」/ 新版 subTitle（服务端已排好版，直接显示） */
    val firstListenWhen: String? = null,
    /** 第一次听的具体日期文案：旧版 date / 新版 desc（同为服务端排版好的字符串） */
    val firstListenDate: String? = null,
    /** 推荐标签（官方 songBizTag creative，如「思念 / 浪漫 / 治愈」） */
    val bizTags: List<String> = emptyList(),
    /** 获奖成就（官方 songAward creative 的资源标题） */
    val awards: List<String> = emptyList(),
)

/** 下载失败条目（下载管理页用）：歌曲 + 最近一次失败原因 */
data class FailedDownload(val song: Song, val reason: String)

/** 听歌排行条目：playCount 播放次数，score 热度 0-100 */
@Immutable
data class PlayRecord(val song: Song, val playCount: Int, val score: Int, val firstPlayMs: Long? = null)

/** 云村评论条目（只读展示） */
@Immutable
/**
 * 艺人乐迷团帖子。
 *
 * 数据来自 `/api/event/get`（`{code, more, event:[…], lasttime}`），每条 event 的结构见
 * `NcmApi.eventToPost` 的 KDoc。⚠ 别改成通用递归解析：`user.commonIdentity.title`
 * 这类**徽章文案**（实测值为 `CHIEF`）会被当成正文，生成一条莫名其妙的假帖。
 *
 * @param id 跨页稳定唯一键（优先 discussId）
 * @param threadId 评论线程 `A_EV_2_{资源id}_{作者uid}`：评论列表 / 发评论 / 点赞都用它
 * @param resourceId 动态资源 id（发评论时回传）
 * @param liked 当前账号是否已点赞
 */
data class ArtistPost(
    val id: String,
    val author: String,
    val avatarUrl: String?,
    val text: String,
    val images: List<String> = emptyList(),
    val timeMs: Long = 0L,
    val likeCount: Long = 0L,
    val commentCount: Long = 0L,
    val threadId: String = "",
    val resourceId: Long = 0L,
    val liked: Boolean = false,
    /** 作者 uid（`A_EV_2_{rid}_{uid}` 里的 uid，点赞/评论列表兜底用） */
    val authorId: Long = 0L,
)

data class CommentItem(
    val user: String,
    val avatar: String?,
    val content: String,
    val liked: Int,
    val time: String,
    /** 评论 id（点赞评论用） */
    val id: Long = 0L,
    /** 我是否已赞这条评论 */
    val mine: Boolean = false,
    /** 该评论的回复条数（2026-10-01 评论区二级：主楼显示「N 条回复」） */
    val replyCount: Int = 0,
    /** 这条评论是在回复谁（`beReplied[0].user.nickname`；空 = 不是回复别人的楼中楼） */
    val replyToUser: String = "",
)

/** 热搜词条：score 用于热度条归一化 */
@Immutable
data class HotSearch(val word: String, val score: Int, val iconUrl: String?)

@Immutable
data class AccountEntry(
    val musicU: String,
    val uid: Long,
    val nickname: String,
    val avatarUrl: String?,
    /** 登录回包下发的 __csrf，写操作（点赞/评论）的 checkToken 用它 */
    val csrf: String? = null,
)
