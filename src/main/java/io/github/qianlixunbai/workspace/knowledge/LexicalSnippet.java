package io.github.qianlixunbai.workspace.knowledge;

import java.util.*;

final class LexicalSnippet {
    static final int UNITS=384,MAX_RANGES=16;
    public record Range(int start,int end) {}
    record Snippet(String text,List<Range> ranges) {
        @Override public String toString(){return "Snippet[redacted]";}
    }
    static String bounded(String value,int units) {
        if(value==null)return null;int end=Math.min(value.length(),units);
        if(end<value.length()&&Character.isLowSurrogate(value.charAt(end)))end--;
        return value.substring(0,end);
    }
    private static List<Range> matches(String text,Set<String> query) {
        List<Range> result=new ArrayList<>();int start=-1;
        for(int i=0;i<=text.length();) {
            int cp=i==text.length()?-1:text.codePointAt(i);
            boolean letter=cp>=0&&(Character.isLetterOrDigit(cp)||Character.getType(cp)==Character.NON_SPACING_MARK);
            if(letter&&start<0)start=i;
            if(!letter&&start>=0){
                if(LexicalAnalyzer.tokens(text.substring(start,i)).stream().anyMatch(query::contains))result.add(new Range(start,i));start=-1;
            }
            if(cp<0)break;i+=Character.charCount(cp);
        }
        return result;
    }
    static Snippet create(String chunk,List<String> query) {
        Set<String> terms=new HashSet<>(query);var matches=matches(chunk,terms);
        int start=matches.isEmpty()?0:Math.max(0,matches.getFirst().start()-48);
        if(start>0&&Character.isLowSurrogate(chunk.charAt(start)))start--;
        String text=bounded(chunk.substring(start),UNITS);List<Range> ranges=new ArrayList<>();
        for(var range:matches){int from=Math.max(0,range.start()-start),to=Math.min(text.length(),range.end()-start);
            if(from<to&&ranges.size()<MAX_RANGES)ranges.add(new Range(from,to));}
        return new Snippet(text,List.copyOf(ranges));
    }
}
