package com.tom.rv2ide.language.services.kotlin.backend.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinParserMessageTest {

  @Test
  fun catalogsHaveCompleteEnglishBaseline() {
    val english = KotlinParserMessages.loadedMessages("en")
    val chinese = KotlinParserMessages.loadedMessages("zh")
    assertFalse(english.isEmpty())
    assertFalse(chinese.isEmpty())
    assertEquals(english.keys, chinese.keys)
    assertTrue(english.values.all { it.isNotBlank() })
    assertTrue(chinese.values.all { it.isNotBlank() })
    assertTrue(english.all { (key, value) -> key == value })
    assertTrue(english.containsKey("Expecting a top level declaration"))
    assertTrue(english.containsKey("Expecting an expression"))
    assertTrue(english.containsKey("Unexpected type specification"))
    assertTrue(english.containsKey("Expecting \"file:\" prefix for file annotations"))
    assertTrue(english.containsKey("Missing '}"))
    assertTrue(english.containsKey("Expecting ')"))
  }

  @Test
  fun translatesKnownParserMessagesAndFallsBackToEnglish() {
    assertEquals(
        "此处需要顶层声明。",
        KotlinParserMessages.messageFor("Expecting a top level declaration", "zh"),
    )
    assertEquals(
        "此处需要表达式。",
        KotlinParserMessages.messageFor("Expecting an expression", "zh"),
    )
    assertEquals(
        "此处需要成员声明。",
        KotlinParserMessages.messageFor("Expecting member declaration", "zh"),
    )
    assertEquals(
        "此处需要属性名或接收者类型。",
        KotlinParserMessages.messageFor("Expecting property name or receiver type", "zh"),
    )
    assertEquals(
        "file 注解需要以 \"file:\" 为前缀。",
        KotlinParserMessages.messageFor(
            "Expecting \"file:\" prefix for file annotations",
            "zh",
        ),
    )
    assertEquals(
        "Unknown parser message.",
        KotlinParserMessages.messageFor("Unknown parser message.", "zh"),
    )
    assertEquals(
        "Expecting a top level declaration",
        KotlinParserMessages.messageFor("Expecting a top level declaration", "en"),
    )
  }

  @Test
  fun translatesAdditionalParserMessages() {
    val chinese = KotlinParserMessages.loadedMessages("zh")
    assertEquals(
        "出现意外的类型说明。",
        chinese["Unexpected type specification"],
    )
    assertEquals(
        "此处需要 ')'。",
        chinese["Expecting ')'"],
    )
  }
}