package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import java.text.Normalizer;
import java.util.*;
import static io.github.qianlixunbai.workspace.knowledge.KnowledgeParser.error;

/** Application-owned tokens. FTS receives only ASCII hex identifiers, never user syntax. */
public final class LexicalAnalyzer {
    public static final String VERSION="lexical-1";
    private LexicalAnalyzer() {}
    static boolean cjk(int cp) {
        var script=Character.UnicodeScript.of(cp);
        return script==Character.UnicodeScript.HAN||script==Character.UnicodeScript.HIRAGANA
            ||script==Character.UnicodeScript.KATAKANA||script==Character.UnicodeScript.HANGUL;
    }
    private static String token(String prefix,String value) {
        var result=new StringBuilder(prefix);
        value.codePoints().forEach(cp->result.append(Integer.toHexString(cp)).append('x'));
        return result.toString();
    }
    public static List<String> tokens(String text) {
        String normalized=Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> out=new ArrayList<>();StringBuilder word=new StringBuilder();int previous=-1;
        for(int cp:normalized.codePoints().toArray()) {
            if(cjk(cp)) {
                if(!word.isEmpty()){out.add(token("w",word.toString()));word.setLength(0);}
                String current=new String(Character.toChars(cp));out.add(token("u",current));
                if(previous>=0)out.add(token("b",new String(Character.toChars(previous))+current));previous=cp;
            } else {
                previous=-1;
                if(Character.isLetterOrDigit(cp)||!word.isEmpty()&&Character.getType(cp)==Character.NON_SPACING_MARK)word.appendCodePoint(cp);
                else if(!word.isEmpty()){out.add(token("w",word.toString()));word.setLength(0);}
            }
        }
        if(!word.isEmpty())out.add(token("w",word.toString()));return List.copyOf(out);
    }
    public record Query(List<String> tokens,String expression) {
        @Override public String toString(){return "LexicalQuery[redacted]";}
    }
    public static Query query(String raw) {
        if(raw==null||raw.isBlank())throw error(ErrorCode.KNOWLEDGE_SEARCH_INVALID);
        if(raw.codePointCount(0,raw.length())>128)throw error(ErrorCode.KNOWLEDGE_QUERY_TOO_COMPLEX);
        for(int i=0;i<raw.length();) {
            int cp=raw.codePointAt(i);if(cp>=0xD800&&cp<=0xDFFF||Character.isISOControl(cp))throw error(ErrorCode.KNOWLEDGE_SEARCH_INVALID);
            i+=Character.charCount(cp);
        }
        var unique=new TreeSet<>(tokens(raw));
        if(unique.isEmpty())throw error(ErrorCode.KNOWLEDGE_SEARCH_INVALID);
        if(unique.size()>32)throw error(ErrorCode.KNOWLEDGE_QUERY_TOO_COMPLEX);
        return new Query(List.copyOf(unique),String.join(" AND ",unique.stream().map(t->"\""+t+"\"").toList()));
    }
    static String stream(String value){return String.join(" ",tokens(value));}
}
