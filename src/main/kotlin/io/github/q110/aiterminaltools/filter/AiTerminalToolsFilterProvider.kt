// ConsoleFilterProvider implementation — registers the core Filter for consoles/terminals
package io.github.q110.aiterminaltools.filter

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.Project

class AiTerminalToolsFilterProvider : ConsoleFilterProvider {
    /** Create an independent Filter per project for project-indexed path parsing */
    override fun getDefaultFilters(project: Project): Array<Filter> {
        return arrayOf(AiTerminalToolsFilter(project))
    }
}
