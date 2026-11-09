package io.github.qianlixunbai.workspace.capability.knowledge;

import io.github.qianlixunbai.workspace.capability.TextTaskSubmission;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.knowledge.KnowledgeEvidenceAdmission;
import io.github.qianlixunbai.workspace.knowledge.KnowledgeEvidenceAdmission.*;
import io.github.qianlixunbai.workspace.knowledge.KnowledgeEvidenceExcerpt;
import io.github.qianlixunbai.workspace.knowledge.LexicalAnalyzer;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import io.github.qianlixunbai.workspace.task.TaskView;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

@Service
public final class KnowledgeAnswerService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final KnowledgeEvidenceAdmission evidence;
    private final TextTaskSubmission tasks;
    private final io.github.qianlixunbai.workspace.knowledge.KnowledgeAnswerHistory history;
    public KnowledgeAnswerService(KnowledgeEvidenceAdmission evidence, TextTaskSubmission tasks,
            io.github.qianlixunbai.workspace.knowledge.KnowledgeAnswerHistory history) { this.evidence=evidence;this.tasks=tasks;this.history=history; }
    private record ModelEvidence(String label,String title,String heading,String text,boolean excerpt) {
        @Override public String toString(){return "ModelEvidence[redacted]";}
    }
    private record ModelInput(String question,List<ModelEvidence> evidence) {
        @Override public String toString(){return "KnowledgeModelInput[redacted]";}
    }
    private static String serialize(String question,List<Evidence> items,boolean excerpt) {
        return JSON.writeValueAsString(new ModelInput(question,items.stream()
                .map(e->new ModelEvidence(e.label(),e.citation().title(),e.citation().heading(),e.text(),excerpt)).toList()));
    }
    public TaskView submit(KnowledgeAnswerRequest request) {
        if(!ClientIdentity.current().equals(ClientIdentity.NATIVE))throw new WorkspaceException(ErrorCode.POLICY_DENIED,"CAPABILITY");
        if(request==null||request.question()==null||request.question().isBlank())throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"REQUEST");
        Snapshot snapshot=evidence.capture(request.query()); // Store lock ends before packing/queue/provider execution.
        var reservation=tasks.reserve("knowledge-answer","chat.balanced",KnowledgeAnswerPrompt.VERSION);
        try {
            if(!tasks.fitsInput(reservation,request.question()))throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"INPUT_BUDGET");
            List<Evidence> prefix=new ArrayList<>();String input=null;
            for(var next:snapshot.items()) {
                List<Evidence> proposed=new ArrayList<>(prefix);proposed.add(next);
                String serialized=serialize(request.question(),proposed,false);
                if(!tasks.fitsInput(reservation,serialized)) {
                    // Keep normal full-chunk ranked prefixes unchanged. Only an oversized
                    // first hit may use a sentence window, with exact source coordinates.
                    if(prefix.isEmpty()) {
                        Evidence excerpt=KnowledgeEvidenceExcerpt.select(next,LexicalAnalyzer.query(request.query()).tokens(),
                                candidate->tasks.fitsInput(reservation,serialize(request.question(),List.of(candidate),true)));
                        if(excerpt!=null){prefix.add(excerpt);input=serialize(request.question(),prefix,true);}
                    }
                    break;
                }
                prefix=proposed;input=serialized;
            }
            if(input==null)throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"KNOWLEDGE_EVIDENCE_BUDGET");
            Snapshot admitted=new Snapshot(prefix);
            UUID id=UUID.randomUUID();
            return tasks.submitPersistent(reservation,"knowledge-answer",KnowledgeAnswerPrompt.SYSTEM,
                    input,output->KnowledgeAnswerParser.parse(output,admitted),id,history::finish,
                    ()->history.begin(id,request.question(),request.query(),reservation.profile().publicInfo(),reservation.promptVersion()));
        } finally { reservation.closeUnlessTransferred(); }
    }
}
