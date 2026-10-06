package io.github.qianlixunbai.workspace.capability.knowledge;

import io.github.qianlixunbai.workspace.capability.TextTaskSubmission;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.knowledge.KnowledgeEvidenceAdmission;
import io.github.qianlixunbai.workspace.knowledge.KnowledgeEvidenceAdmission.*;
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
    public KnowledgeAnswerService(KnowledgeEvidenceAdmission evidence, TextTaskSubmission tasks) { this.evidence=evidence;this.tasks=tasks; }
    private record ModelEvidence(String label,String title,String heading,String text) {
        @Override public String toString(){return "ModelEvidence[redacted]";}
    }
    private record ModelInput(String question,List<ModelEvidence> evidence) {
        @Override public String toString(){return "KnowledgeModelInput[redacted]";}
    }
    public TaskView submit(KnowledgeAnswerRequest request) {
        if(!ClientIdentity.current().equals(ClientIdentity.NATIVE))throw new WorkspaceException(ErrorCode.POLICY_DENIED,"CAPABILITY");
        if(request==null||request.question()==null||request.question().isBlank())throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"REQUEST");
        if(!tasks.fitsInput("chat.balanced",request.question()))throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"INPUT_BUDGET");
        Snapshot snapshot=evidence.capture(request.query()); // Store lock ends before packing/queue/provider execution.
        List<Evidence> prefix=new ArrayList<>();String input=null;
        for(var next:snapshot.items()) {
            List<Evidence> proposed=new ArrayList<>(prefix);proposed.add(next);
            String serialized=JSON.writeValueAsString(new ModelInput(request.question(),proposed.stream()
                    .map(e->new ModelEvidence(e.label(),e.citation().title(),e.citation().heading(),e.text())).toList()));
            if(!tasks.fitsInput("chat.balanced",serialized))break;
            prefix=proposed;input=serialized;
        }
        if(input==null)throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"KNOWLEDGE_EVIDENCE_BUDGET");
        Snapshot admitted=new Snapshot(prefix);
        return tasks.submitMapped("knowledge-answer","chat.balanced",KnowledgeAnswerPrompt.VERSION,KnowledgeAnswerPrompt.SYSTEM,
                input,input.length(),output->KnowledgeAnswerParser.parse(output,admitted));
    }
}
