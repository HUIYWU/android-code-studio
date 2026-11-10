package com.tom.rv2ide.language.services.kotlin.backend.analysis

import java.text.MessageFormat
import org.jetbrains.kotlin.fir.analysis.diagnostics.CliFrontendDiagnostics
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors
import org.jetbrains.kotlin.fir.builder.FirSyntaxErrors
import org.jetbrains.kotlin.diagnostics.SimpleKtDiagnosticRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KotlinDiagnosticTemplateTest {

  @Test
  fun templatesMatchCompilerFactories() {
    val factories =
        listOf(FirErrors, FirJvmErrors, FirSyntaxErrors, CliFrontendDiagnostics)
            .flatMap { it.getRendererFactory().MAP.factories }
    val names = factories.map { it.name }.toSet()
    val byName = factories.associateBy { it.name }
    val templates = KotlinDiagnosticTemplates.loadedTemplates("zh")
    val englishTemplates = KotlinDiagnosticTemplates.loadedTemplates("en")
    assertFalse(templates.isEmpty())
    assertFalse(englishTemplates.isEmpty())
    assertEquals(templates.keys, englishTemplates.keys)
    assertEquals(emptySet<String>(), templates.keys - names)
    templates.forEach { (name, template) ->
      assertFalse(name, template.isBlank())
      val factory = requireNotNull(byName[name]) { "Unknown diagnostic factory: $name" }
      if (factory.ktRenderer !is SimpleKtDiagnosticRenderer && '{' in template) {
        MessageFormat(template)
      }
      val english = factory.ktRenderer.message
      assertEquals(name, english, englishTemplates[name])
      assertEquals(name, placeholders(english), placeholders(template))
    }
  }

  private fun placeholders(template: String): Set<String> =
      Regex("\\{(\\d+)\\}").findAll(template).map { it.groupValues[1] }.toSet()
}