package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.knowledge.KnowledgeEvidenceAdmission.Evidence;
import io.github.qianlixunbai.workspace.task.TaskResult;
import java.text.BreakIterator;
import java.util.*;
import java.util.function.Predicate;

/** A contiguous sentence window in an already captured authoritative chunk, never a search snippet. */
public final class KnowledgeEvidenceExcerpt {
    private KnowledgeEvidenceExcerpt() {}

    public static Evidence select(Evidence source,List<String> query,Predicate<Evidence> fits) {
        String text=source.text();
        var sentences=BreakIterator.getSentenceInstance(Locale.ROOT);sentences.setText(text);
        List<Integer> boundaries=new ArrayList<>();
        for(int at=sentences.first();at!=BreakIterator.DONE;at=sentences.next())boundaries.add(at);
        int size=boundaries.size()-1;
        if(size<2)return null; // Do not cut an oversized sentence or manufacture its context.
        Set<String> metadata=new HashSet<>(LexicalAnalyzer.tokens(source.citation().title()));
        if(source.citation().heading()!=null)metadata.addAll(LexicalAnalyzer.tokens(source.citation().heading()));
        var required=query.stream().filter(token->!metadata.contains(token)).distinct().toList();
        int bestLeft=0,bestRight=0;
        if(!required.isEmpty()) {
            List<Set<String>> terms=new ArrayList<>();
            for(int i=0;i<size;i++)terms.add(new HashSet<>(LexicalAnalyzer.tokens(text.substring(boundaries.get(i),boundaries.get(i+1)))));
            int[] counts=new int[required.size()];int covered=0,left=0,width=Integer.MAX_VALUE;
            bestLeft=-1;
            for(int right=0;right<size;right++) {
                for(int k=0;k<required.size();k++)if(terms.get(right).contains(required.get(k))&&counts[k]++==0)covered++;
                while(covered==required.size()&&left<=right) {
                    int units=boundaries.get(right+1)-boundaries.get(left);
                    if(units<width){width=units;bestLeft=left;bestRight=right;}
                    for(int k=0;k<required.size();k++)if(terms.get(left).contains(required.get(k))&&--counts[k]==0)covered--;
                    left++;
                }
            }
            if(bestLeft<0)return null;
        }
        // Keep at least one adjacent sentence on each available side. Never shrink
        // just to the matching words; metadata-only matches start at the chunk's beginning.
        int low=1,high=Math.max(bestLeft,size-1-bestRight);Evidence admitted=null;
        while(low<=high) {
            int padding=low+(high-low)/2;
            int start=boundaries.get(Math.max(0,bestLeft-padding));
            int end=boundaries.get(Math.min(size,bestRight+padding+1));
            Evidence candidate=slice(source,start,end);
            if(fits.test(candidate)){admitted=candidate;low=padding+1;}else high=padding-1;
        }
        return admitted;
    }

    private static Evidence slice(Evidence source,int start,int end) {
        String text=source.text();var old=source.citation();
        int first=old.startLine(),last;
        for(int i=0;i<start;i++)if(text.charAt(i)=='\n')first++;
        last=first;
        for(int i=start;i<end;i++)if(text.charAt(i)=='\n')last++;
        if(text.charAt(end-1)=='\n')last--;
        var citation=new TaskResult.KnowledgeCitation(old.documentId(),old.title(),old.sourceRevision(),old.sourceType(),
                old.startOffset()+start,old.startOffset()+end,first,last,old.heading(),old.locator());
        return new Evidence(source.label(),citation,text.substring(start,end),source.representationDigest());
    }
}
