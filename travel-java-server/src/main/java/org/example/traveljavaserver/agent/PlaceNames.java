package org.example.traveljavaserver.agent;

/** Formatting equivalence only. Never removes words, branch names or punctuation. */
final class PlaceNames {
  private PlaceNames() {}
  static String key(String text) {
    if (text == null) return "";
    var result=new StringBuilder();
    text.codePoints().map(c->c>=0xFF01&&c<=0xFF5E?c-0xFEE0:c)
        .filter(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c)).forEach(result::appendCodePoint);
    return result.toString();
  }
}
