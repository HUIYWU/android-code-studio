/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.java.kotlin;

import jdkx.lang.model.element.AnnotationMirror;
import jdkx.lang.model.element.AnnotationValue;
import jdkx.lang.model.element.Element;
import jdkx.lang.model.element.ElementKind;

/** Identifies implementation-only members emitted into Kotlin ABI Java stubs. */
public final class KotlinAbiSyntheticMembers {

  public static final String SYNTHETIC_CONSTRUCTOR_WARNING =
      "__kotlin_abi_synthetic_constructor__";

  private KotlinAbiSyntheticMembers() {}

  public static boolean isSyntheticConstructor(Element element) {
    if (element == null || element.getKind() != ElementKind.CONSTRUCTOR) {
      return false;
    }
    for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
      if (!"java.lang.SuppressWarnings".equals(annotation.getAnnotationType().toString())) {
        continue;
      }
      for (AnnotationValue value : annotation.getElementValues().values()) {
        if (containsWarningValue(value, SYNTHETIC_CONSTRUCTOR_WARNING)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean containsWarningValue(AnnotationValue value, String expected) {
    final Object raw = value.getValue();
    if (expected.equals(raw)) {
      return true;
    }
    if (raw instanceof java.util.List<?>) {
      for (Object item : (java.util.List<?>) raw) {
        if (item instanceof AnnotationValue
            && containsWarningValue((AnnotationValue) item, expected)) {
          return true;
        }
      }
    }
    return false;
  }
}
