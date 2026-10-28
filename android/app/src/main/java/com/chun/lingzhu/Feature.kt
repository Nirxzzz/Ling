package com.chun.lingzhu

import android.content.Context
import android.content.Intent

/**
 * v2.4「功能包」协议：一个功能（学习 / 记事 / 知识图谱 / 画像…）对自己的一切声明。
 *
 * ## 为什么要有这个
 * 改之前，加一个功能要同时动三处：MainActivity 的设置页（2930 行）、
 * OrbService 的菜单（2700 行）、ChatListActivity 的入口。三处都是巨型文件，
 * 每次都要在几千行里找落点，撞车和漏改是常态。
 *
 * 这个协议把「一个功能长什么样」收进一个实现类里：它叫什么、图标是哪个、
 * 点进去打开哪个页面、它提供哪些 Agent 工具、它有哪些可配置项、它有没有独立入口。
 * 主壳只遍历一份清单，不再认识具体功能。
 *
 * ## 设计约束
 *  - **只加不改**：现有 Activity / 工具 / 设置项一行不动，先让它们「被描述」，
 *    再逐步把主壳里的硬编码换成遍历。这样每一步都能编译、能测、能回滚。
 *  - **纯数据 + 轻函数**：Feature 本身不持 Context、不做 IO，
 *    需要的 Context 从方法参数进来，保证可单测。
 *  - **不引入反射 / 动态加载**：本项目零第三方依赖，动态 dex 会带来权限、
 *    资源、签名的连锁问题，收益远不抵成本。
 */
interface Feature {

    /** 稳定标识，用于去重、排序、持久化（用户自定义显隐时按这个存）。改了就等同于换了个功能 */
    val id: String

    /** 给人看的名字，出现在入口列表 / 设置分组标题 */
    val title: String

    /** 一句话说明，可为空。为空时界面不显示副标题 */
    val subtitle: String get() = ""

    /** 排序权重，小的在前。同一权重按注册顺序 */
    val order: Int get() = 500

    /**
     * 这个功能提供哪些 Agent 工具名。
     *
     * 与 AgentTools.registry 的关系：这里是**声明**，registry 是**实现**。
     * 声明出来是为了让「工具权限」对话框能按功能分组显示，
     * 而不是甩给用户 12 个光秃秃的工具名。
     */
    val toolNames: List<String> get() = emptyList()

    /**
     * 打开这个功能的主页面。返回 false = 这个功能当前打不开（比如没数据 / 没权限），
     * 调用方据此给出提示，而不是让用户面对一个闪一下就回去的白屏。
     *
     * 默认 false：子类必须显式声明自己能打开什么，避免「继承了个空实现还以为能点」。
     */
    fun open(ctx: Context): Boolean = false

    /** 构造打开用的 Intent；不能直接打开（比如需要先选数据）时返回 null */
    fun intent(ctx: Context): Intent? = null

    /**
     * 这个功能在设置页要不要一个入口。
     *
     * 很多功能是「被动」的（比如专注守护，只在后台判断），设置页给个入口只是噪音。
     */
    val showsInSettings: Boolean get() = true
}
