package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

internal object ReferenceConventions {
  const val ARRAY_ACCESS = 1
  const val OPERATOR = 1 shl 1
  const val DESTRUCTURING = 1 shl 2
  const val FOR_LOOP = 1 shl 3
  const val DELEGATE = 1 shl 4
  const val INVOKE = 1 shl 5
  const val ALL = 63

  fun forTarget(name: String?): Int {
    val targetName = name ?: return 0
    return when {
      targetName == "get" || targetName == "set" -> ARRAY_ACCESS or OPERATOR
      targetName == "invoke" -> INVOKE
      targetName in operatorNames -> OPERATOR
      targetName.startsWith("component") &&
          targetName.removePrefix("component").toIntOrNull() != null -> DESTRUCTURING
      targetName == "iterator" || targetName == "hasNext" || targetName == "next" -> FOR_LOOP
      targetName == "getValue" || targetName == "setValue" || targetName == "provideDelegate" ->
          DELEGATE
      else -> 0
    }
  }

  private val operatorNames =
      setOf(
          "plus",
          "minus",
          "times",
          "div",
          "rem",
          "plusAssign",
          "minusAssign",
          "timesAssign",
          "divAssign",
          "remAssign",
          "inc",
          "dec",
          "unaryPlus",
          "unaryMinus",
          "not",
          "contains",
          "compareTo",
          "rangeTo",
          "rangeUntil",
          "equals",
      )
}