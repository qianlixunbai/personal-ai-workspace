package io.github.qianlixunbai.workspace.knowledge;

import java.util.*;
import java.util.function.Consumer;

/** Exact offsets into K1 normalized truth, preserving each structural block. */
public final class LexicalChunker {
    public static final String VERSION="lexical-chunk-1";
    public static final int CODE_POINTS=2048,MAX_CHUNKS=100_000;
    public record Chunk(int ordinal,int startOffset,int endOffset,int startLine,int endLine,String heading) {}
    private LexicalChunker() {}
    public static void chunks(KnowledgeParser.Representation source,Consumer<Chunk> consumer) {
        String text=source.text();int ordinal=0;
        for(var locator:source.locators()) {
            int start=locator.startOffset(),line=locator.startLine();
            while(start<locator.endOffset()) {
                int end=start;for(int points=0;points<CODE_POINTS&&end<locator.endOffset();points++)end+=Character.charCount(text.codePointAt(end));
                if(end<locator.endOffset()) {
                    int boundary=-1;
                    for(int i=end;i>start;){int cp=text.codePointBefore(i);if(cp=='\n'){boundary=i;break;}i-=Character.charCount(cp);}
                    if(boundary<0)for(int i=end;i>start;){int cp=text.codePointBefore(i);if(Character.isWhitespace(cp)){boundary=i;break;}i-=Character.charCount(cp);}
                    if(boundary>start)end=boundary;
                }
                int newlines=0;for(int i=start;i<end;i++)if(text.charAt(i)=='\n')newlines++;
                int lastLine=line+newlines-(text.charAt(end-1)=='\n'?1:0);
                consumer.accept(new Chunk(ordinal++,start,end,line,lastLine,locator.heading()));
                line+=newlines;start=end;
            }
        }
    }
}
