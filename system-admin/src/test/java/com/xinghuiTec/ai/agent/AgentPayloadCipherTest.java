package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghuiTec.agent.api.AgentProblem;
import com.xinghuiTec.config.SecretCipher;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class AgentPayloadCipherTest {
    final AgentPayloadCipher codec=new AgentPayloadCipher(new SecretCipher(()->Base64.getEncoder().encodeToString(new byte[32])));
    final String purpose="agent:tenant-a:user-a:session-a:state";
    @Test void multilineUnicodeAndLongNativeStateRoundTripWithTheExistingKey() {
        String text="业务记录和工具历史\n\t\"字段\":\"值\"\n".repeat(5000);
        String sealed=codec.seal(purpose,text);assertFalse(sealed.contains("业务记录"));assertEquals(text,codec.open(purpose,sealed));
        assertEquals("",codec.open(purpose,codec.seal(purpose,"")));
    }
    @Test void otherTenantSlotKeyAndSplicedEncryptedChunksCannotReadOrModifyHistory()throws Exception {
        String sealed=codec.seal(purpose,"first fixture ".repeat(6000));var json=new ObjectMapper();
        assertThrows(AgentProblem.class,()->codec.open("agent:tenant-b:user-a:session-a:state",sealed));
        var first=json.readTree(sealed);var second=json.readTree(codec.seal(purpose,"other fixture ".repeat(6000)));
        ((com.fasterxml.jackson.databind.node.ArrayNode)first.get("parts")).set(0,second.path("parts").get(0));
        assertThrows(AgentProblem.class,()->codec.open(purpose,first.toString()));
        var wrong=new AgentPayloadCipher(new SecretCipher(()->Base64.getEncoder().encodeToString(new byte[]{1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1})));
        assertThrows(AgentProblem.class,()->wrong.open(purpose,sealed));
    }
    @Test void truncationMetadataTamperingAndLargePayloadsFailClosed()throws Exception {
        var json=new ObjectMapper();String sealed=codec.seal(purpose,"fixture ".repeat(6000));
        var parts=json.readTree(sealed);((com.fasterxml.jackson.databind.node.ArrayNode)parts.get("parts")).remove(0);
        assertThrows(AgentProblem.class,()->codec.open(purpose,parts.toString()));
        String changed=sealed.replace("\"bytes\":48000","\"bytes\":47999");assertNotEquals(sealed,changed);
        assertThrows(AgentProblem.class,()->codec.open(purpose,changed));
        assertEquals("STATE_LIMIT",assertThrows(AgentProblem.class,()->codec.seal(purpose,"x".repeat(4*1024*1024+1))).code());
    }
}
