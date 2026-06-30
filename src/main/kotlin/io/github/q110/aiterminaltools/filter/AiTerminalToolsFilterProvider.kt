// ConsoleFilterProvider implementation - registers the core filter for consoles/terminals
package io.github.q110.aiterminaltools.filter

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.Project

class AiTerminalToolsFilterProvider : ConsoleFilterProvider {
    /** Create a separate filter for each project so file paths can be resolved with the project index. */
    override fun getDefaultFilters(project: Project): Array<Filter> {
        return arrayOf(AiTerminalToolsFilter(project))
    }
}
