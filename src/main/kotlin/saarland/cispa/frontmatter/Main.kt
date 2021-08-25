package saarland.cispa.frontmatter

import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.CliktHelpFormatter
import saarland.cispa.frontmatter.commands.ApiAnalysisCommand
import saarland.cispa.frontmatter.commands.CollectApiCommand
import saarland.cispa.frontmatter.commands.FullAnalysisCommand
import saarland.cispa.frontmatter.commands.LangAnalysisCommand
import saarland.cispa.frontmatter.commands.UiAnalysisCommand

fun main(
    args: Array<String>
): Unit = NoOpCliktCommand(help = "Use one of the commands from the list to perform the analysis", name = "frontmatter.jar", printHelpOnEmptyArgs = true)
    .subcommands(UiAnalysisCommand(), ApiAnalysisCommand(), FullAnalysisCommand(), LangAnalysisCommand(), CollectApiCommand())
    .context { helpFormatter = CliktHelpFormatter(showDefaultValues = true, showRequiredTag = true) }
    .main(args)
