package org.apache.hadoop.hive.thrift;

import java.io.IOException;
import java.util.List;

import junit.framework.TestCase;

import org.apache.hadoop.hive.metastore.HiveMetaStore.HMSHandler;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.thrift.DelegationTokenStore.TokenStoreException;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.token.delegation.AbstractDelegationTokenSecretManager.DelegationTokenInformation;
import org.apache.hadoop.security.token.delegation.HiveDelegationTokenSupport;
import org.junit.Assert;

public class TestDBTokenStore extends TestCase{

  public void testDBTokenStore() throws TokenStoreException, MetaException, IOException {

    DelegationTokenStore ts = new DBTokenStore();
    ts.setStore(new HMSHandler("Test handler"));
    assertEquals(0, ts.getMasterKeys().length);
    assertEquals(false,ts.removeMasterKey(-1));
    try{
      ts.updateMasterKey(-1, "non-existent-key");
      fail("Updated non-existent key.");
    } catch (TokenStoreException e) {
      assertTrue(e.getCause() instanceof NoSuchObjectException);
    }
    int keySeq = ts.addMasterKey("key1Data");
    int keySeq2 = ts.addMasterKey("key2Data");
    int keySeq2same = ts.addMasterKey("key2Data");
    assertEquals("keys sequential", keySeq + 1, keySeq2);
    assertEquals("keys sequential", keySeq + 2, keySeq2same);
    assertEquals("expected number of keys", 3, ts.getMasterKeys().length);
    assertTrue(ts.removeMasterKey(keySeq));
    assertTrue(ts.removeMasterKey(keySeq2same));
    assertEquals("expected number of keys", 1, ts.getMasterKeys().length);
    assertEquals("key2Data",ts.getMasterKeys()[0]);
    ts.updateMasterKey(keySeq2, "updatedData");
    assertEquals("updatedData",ts.getMasterKeys()[0]);
    assertTrue(ts.removeMasterKey(keySeq2));

    // tokens
    assertEquals(0, ts.getAllDelegationTokenIdentifiers().size());
    DelegationTokenIdentifier tokenId = new DelegationTokenIdentifier(
        new Text("owner"), new Text("renewer"), new Text("realUser"));
    assertNull(ts.getToken(tokenId));
    assertFalse(ts.removeToken(tokenId));
    DelegationTokenInformation tokenInfo = new DelegationTokenInformation(
        99, "password".getBytes());
    assertTrue(ts.addToken(tokenId, tokenInfo));
    assertFalse(ts.addToken(tokenId, tokenInfo));
    DelegationTokenInformation tokenInfoRead = ts.getToken(tokenId);
    assertEquals(tokenInfo.getRenewDate(), tokenInfoRead.getRenewDate());
    assertNotSame(tokenInfo, tokenInfoRead);
    Assert.assertArrayEquals(HiveDelegationTokenSupport
        .encodeDelegationTokenInformation(tokenInfo),
        HiveDelegationTokenSupport
            .encodeDelegationTokenInformation(tokenInfoRead));

    List<DelegationTokenIdentifier> allIds = ts
        .getAllDelegationTokenIdentifiers();
    assertEquals(1, allIds.size());
    Assert.assertEquals(TokenStoreDelegationTokenSecretManager
        .encodeWritable(tokenId),
        TokenStoreDelegationTokenSecretManager.encodeWritable(allIds
            .get(0)));

    assertTrue(ts.removeToken(tokenId));
    assertEquals(0, ts.getAllDelegationTokenIdentifiers().size());
    assertNull(ts.getToken(tokenId));
    ts.close();
  }
}
