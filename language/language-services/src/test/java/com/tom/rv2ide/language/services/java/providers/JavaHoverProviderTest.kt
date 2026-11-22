package com.tom.rv2ide.language.services.java.providers

import com.tom.rv2ide.language.services.java.psi.JavaPsiHover
import com.tom.rv2ide.language.services.java.utils.MarkdownHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class JavaHoverProviderTest {
  @Test
  fun formatsJavaHoverMarkdownLikeKotlinHover() {
    assertEquals(
        "```java\nclass AndroidDeviceOverlay\n```\n---\nAndroid device overlay.",
        JavaPsiHover.markdown("class AndroidDeviceOverlay", "Android device overlay."),
    )
    assertEquals(
        "```java\nclass AndroidDeviceOverlay\n```",
        JavaPsiHover.markdown("class AndroidDeviceOverlay", ""),
    )
  }

  @Test
  fun decodesNumericHtmlEntitiesInJavaDocumentation() {
    assertEquals(
        "Android设备性能叠加层",
        MarkdownHelper.asMarkdown("Android&#35774;&#22791;&#24615;&#33021;&#21472;&#21152;&#23618;"),
    )
    assertEquals("Android设备", MarkdownHelper.asMarkdown("Android&#x8BBE;&#x5907;"))
    val escape = 92.toChar().toString()
    assertEquals("Android设备", MarkdownHelper.asMarkdown("Android${escape}u8bbe${escape}u5907"))
    assertEquals("Android设备", MarkdownHelper.asMarkdown("Android${escape}uuuu8bbe${escape}uuuu5907"))
    assertEquals("&#1114112;", MarkdownHelper.asMarkdown("&#1114112;"))
  }
}
