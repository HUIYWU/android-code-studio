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

import java.net.URI;
import jdkx.tools.JavaFileObject;
import jdkx.tools.SimpleJavaFileObject;

/** In-memory Java source representing a Kotlin JVM ABI declaration. */
public final class KotlinAbiStubJavaFileObject extends SimpleJavaFileObject {

  public static final String URI_SCHEME = "kotlin-abi";
  private final String source;
  private final long revision;

  public KotlinAbiStubJavaFileObject(String qualifiedName, String source, long revision) {
    super(uriFor(qualifiedName), JavaFileObject.Kind.SOURCE);
    this.source = source;
    this.revision = revision;
  }

  @Override
  public CharSequence getCharContent(boolean ignoreEncodingErrors) {
    return source;
  }

  @Override
  public long getLastModified() {
    return revision;
  }

  public static boolean isKotlinAbiStub(JavaFileObject file) {
    return file != null && URI_SCHEME.equals(file.toUri().getScheme());
  }

  private static URI uriFor(String qualifiedName) {
    return URI.create(URI_SCHEME + ":///" + qualifiedName.replace('.', '/') + ".java");
  }
}
