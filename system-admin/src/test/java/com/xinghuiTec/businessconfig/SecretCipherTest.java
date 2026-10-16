package com.xinghuiTec.businessconfig;
import com.xinghuiTec.config.SecretCipher;
import com.xinghuiTec.exception.ServiceException;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
class SecretCipherTest {
    static SecretCipher cipher() { return new SecretCipher(()->Base64.getEncoder().encodeToString(new byte[32])); }
    @Test void authenticatedEncryptionUsesRandomNonceAndOwnerBinding() {
        var cipher=cipher();
        String first=cipher.encrypt("model:1","neutral-test-key");
        String second=cipher.encrypt("model:1","neutral-test-key");
        assertNotEquals(first,second);assertFalse(first.contains("neutral-test-key"));
        assertEquals("neutral-test-key",cipher.decrypt("model:1",first));
        assertThrows(ServiceException.class,()->cipher.decrypt("model:2",first));
        assertThrows(ServiceException.class,()->cipher.decrypt("model:1",first.substring(0,first.length()-3)+"AAA"));
        assertThrows(ServiceException.class,()->new SecretCipher(()->Base64.getEncoder().encodeToString(new byte[]{1})).decrypt("model:1",first));
    }
    @Test void missingMasterKeyAndMalformedValuesNeverFallBackToPlaintext() {
        assertThrows(ServiceException.class,()->new SecretCipher(()->null).encrypt("test","neutral"));
        assertThrows(ServiceException.class,()->cipher().decrypt("test","plain-value"));
        assertThrows(ServiceException.class,()->cipher().encrypt("test","bad\nvalue"));
    }
    @Test void purposeIsolatedFingerprintIsStableAcrossInstancesAndDifferentFromOtherMasters() {
        byte[] input="neutral canonical fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String first=cipher().fingerprint("ai:request-input:v1",input);
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertEquals(first,cipher().fingerprint("ai:request-input:v1",input));
        assertNotEquals(first,cipher().fingerprint("other:purpose:v1",input));
        byte[] alternate=new byte[32]; alternate[0]=1;
        var different=new SecretCipher(()->Base64.getEncoder().encodeToString(alternate));
        assertNotEquals(first,different.fingerprint("ai:request-input:v1",input));
        assertFalse(first.contains("neutral"));
    }
    @Test void fingerprintNeverFallsBackWhenMasterIsMissingOrMalformed() {
        assertDoesNotThrow(()->new SecretCipher(()->null));
        assertFalse(new SecretCipher(()->null).ready());
        assertThrows(ServiceException.class,()->new SecretCipher(()->null).fingerprint("ai:request-input:v1",new byte[]{1}));
        assertThrows(ServiceException.class,()->new SecretCipher(()->"bad-base64").fingerprint("ai:request-input:v1",new byte[]{1}));
        assertThrows(ServiceException.class,()->cipher().fingerprint("bad\npurpose",new byte[]{1}));
    }
}
