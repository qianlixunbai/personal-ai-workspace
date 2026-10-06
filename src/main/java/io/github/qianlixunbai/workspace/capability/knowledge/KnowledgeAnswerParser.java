package io.github.qianlixunbai.workspace.capability.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.knowledge.KnowledgeEvidenceAdmission.Snapshot;
import io.github.qianlixunbai.workspace.task.TaskResult;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

public final class KnowledgeAnswerParser {
    // K3-specific output bound keeps the worst-case escaped bridge response below 64 KiB.
    public static final int MAX_ANSWER_UNITS=2048;
    private static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private KnowledgeAnswerParser() {}
    public static TaskResult.KnowledgeAnswer parse(String output, Snapshot admitted) {
        JsonNode root;
        try { root=JSON.readTree(output); } catch(tools.jackson.core.JacksonException e) { throw invalid(); }
        if(root==null||!root.isObject()||root.size()!=2||!root.has("answer")||!root.has("citations"))throw invalid();
        var answer=root.get("answer");var labels=root.get("citations");
        if(!answer.isString()||answer.asString().isBlank()||answer.asString().length()>MAX_ANSWER_UNITS
                ||!labels.isArray()||labels.isEmpty()||labels.size()>admitted.items().size())throw invalid();
        String text=answer.asString();
        for(int i=0;i<text.length();) {int cp=text.codePointAt(i);if(cp>=0xD800&&cp<=0xDFFF)throw invalid();i+=Character.charCount(cp);}
        Set<String> seen=new HashSet<>();List<TaskResult.KnowledgeCitation> citations=new ArrayList<>();
        for(var label:labels) {
            if(!label.isString()||!seen.add(label.asString()))throw invalid();
            var item=admitted.items().stream().filter(e->e.label().equals(label.asString())).findFirst().orElseThrow(KnowledgeAnswerParser::invalid);
            citations.add(item.citation());
        }
        return new TaskResult.KnowledgeAnswer(text,citations);
    }
    private static WorkspaceException invalid() { return new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID,"KNOWLEDGE_GROUNDING"); }
}
