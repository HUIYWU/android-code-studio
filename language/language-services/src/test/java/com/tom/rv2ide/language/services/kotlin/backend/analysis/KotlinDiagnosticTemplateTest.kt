package com.tom.rv2ide.language.services.kotlin.backend.analysis

import java.text.MessageFormat
import org.jetbrains.kotlin.fir.analysis.diagnostics.CliFrontendDiagnostics
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors
import org.jetbrains.kotlin.fir.builder.FirSyntaxErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    assertFalse(templates.isEmpty())
    assertTrue(KotlinDiagnosticTemplates.loadedTemplates("en").isEmpty())
    assertEquals(emptySet<String>(), templates.keys - names)
    templates.forEach { (name, template) ->
      assertFalse(name, template.isBlank())
      if ('{' in template) {
        MessageFormat(template)
      }
      val english =
          requireNotNull(byName[name]) { "Unknown diagnostic factory: $name" }.ktRenderer.message
      assertEquals(name, placeholders(english), placeholders(template))
    }
  }

  private fun placeholders(template: String): Set<String> =
      Regex("\\{(\\d+)\\}").findAll(template).map { it.groupValues[1] }.toSet()
}