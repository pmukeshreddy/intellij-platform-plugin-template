package org.jetbrains.plugins.template

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class SweepStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        println("Sweep plugin startup completed for project: ${project.name}")
    }

}
