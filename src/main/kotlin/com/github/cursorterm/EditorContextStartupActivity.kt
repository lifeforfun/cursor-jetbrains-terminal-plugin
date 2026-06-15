package com.github.cursorterm

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/** 项目打开即开始跟踪编辑器标签页与选区，不依赖 Tool Window 是否已打开。 */
class EditorContextStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        EditorContextCollector.installTracking(project, project)
    }
}
