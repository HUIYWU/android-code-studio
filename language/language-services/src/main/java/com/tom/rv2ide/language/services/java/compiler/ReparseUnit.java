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
package com.tom.rv2ide.language.services.java.compiler;

/** Unit of a method declaration that an edited window belongs to. */
enum ReparseUnit {
  /** The window lies inside the method or constructor body. */
  METHOD_BODY,
  /** The window lies inside the written modifiers of the declaration. */
  METHOD_MODIFIERS,
  /** The window lies inside the declared throws types. */
  THROWS_CLAUSE,
  /** The window lies inside a class-member field initializer. */
  FIELD_INITIALIZER,
  /** The window lies inside the written modifiers of a field declaration. */
  FIELD_MODIFIERS,
  /** The window lies inside the written modifiers of a class declaration. */
  CLASS_MODIFIERS,
  /** No unit owns the window. */
  NONE,
}