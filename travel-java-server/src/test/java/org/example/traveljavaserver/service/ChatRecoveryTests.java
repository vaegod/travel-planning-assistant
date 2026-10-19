package org.example.traveljavaserver.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import okhttp3.Call;
import org.example.traveljavaserver.persistence.*;
import org.example.traveljavaserver.utils.LLMutils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.*;

class ChatRecoveryTests {
  StoreMapper store;
  TravelService service;
  LLMutils llm;
  final Map<String,Rows.Conversation> rows=new ConcurrentHashMap<>();
  final AtomicInteger failures=new AtomicInteger(), attempts=new AtomicInteger(), checkpoints=new AtomicInteger();
  volatile String savedStatus, savedAnswer;

  @BeforeEach void setup() {
    store=mock(StoreMapper.class);llm=mock(LLMutils.class);
    when(store.lockConversation(anyLong(),anyString())).thenAnswer(i->rows.get(i.getArgument(1)));
    when(store.context(anyLong(),anyString())).thenReturn(List.of());
    when(store.finishMessage(anyLong(),anyString(),anyString(),anyString(),nullable(String.class),nullable(String.class)))
      .thenAnswer(i->{
        attempts.incrementAndGet();
        if(failures.getAndUpdate(n->Math.max(0,n-1))>0)throw new DataAccessResourceFailureException("fixture write failure");
        savedAnswer=i.getArgument(2);savedStatus=i.getArgument(3);return 1;
      });
    when(store.releaseConversation(anyLong(),anyString(),anyString())).thenAnswer(i->{
      var row=rows.get(i.getArgument(1));
      if(Objects.equals(row.getActiveRequestId(),i.getArgument(2)))row.setActiveRequestId(null);
      return 1;
    });
    when(llm.newChatCall(anyList(),eq(true))).thenAnswer(i->mock(Call.class));
    doAnswer(i->{Consumer<String> content=i.getArgument(1);content.accept("第一段");content.accept("第二段");return null;})
      .when(llm).readStream(any(),any());
    service=new TravelService(llm,new DataService(store,new ChatContext()),180000,25,1,2);
  }
  @AfterEach void close(){failures.set(0);service.close();}
  String conversation() {
    var row=new Rows.Conversation();row.setId(UUID.randomUUID().toString());row.setUserId(1L);
    row.setTitle("新对话");row.setContextJson("{}");rows.put(row.getId(),row);return row.getId();
  }
  String send(String conversation) {
    String id=UUID.randomUUID().toString();service.chat(1,conversation,id,"正常问题");return id;
  }
  void waitUntil(BooleanSupplier condition)throws Exception {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
    while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(10);
    assertTrue(condition.getAsBoolean(),"condition did not become true");
  }
  @Test void checkpointFailureDoesNotInterruptModelOrFinalSave()throws Exception {
    when(store.checkpoint(anyLong(),anyString(),anyString())).thenAnswer(i->{
      checkpoints.incrementAndGet();throw new DataAccessResourceFailureException("fixture checkpoint");
    });
    String conversation=conversation();send(conversation);
    waitUntil(()->service.activeStreamCount()==0);
    assertEquals("第一段第二段",savedAnswer);assertEquals("completed",savedStatus);
    assertEquals(1,checkpoints.get());assertNull(rows.get(conversation).getActiveRequestId());
  }
  @Test void cancellationRetriesTerminalSaveAfterDatabaseRecovers()throws Exception {
    failures.set(1);String conversation=conversation(),id=send(conversation);
    waitUntil(()->attempts.get()>=1 && service.runningStreamCount()==0);
    assertEquals(1,service.activeStreamCount());assertNotNull(rows.get(conversation).getActiveRequestId());
    assertTrue(service.cancel(1,id));assertEquals(0,service.activeStreamCount());
    assertNull(rows.get(conversation).getActiveRequestId());assertEquals("completed",savedStatus);
    assertEquals("第一段第二段",savedAnswer);assertEquals(2,attempts.get());
  }
  @Test void automaticMaintenanceRetriesWithoutClientAction()throws Exception {
    failures.set(1);service.startMaintenance();String conversation=conversation();send(conversation);
    waitUntil(()->service.activeStreamCount()==0);
    assertNull(rows.get(conversation).getActiveRequestId());assertEquals(2,attempts.get());
  }
  @Test void newTurnReconcilesPendingSaveBeforeLockingConversation()throws Exception {
    failures.set(1);String conversation=conversation();send(conversation);
    waitUntil(()->attempts.get()>=1 && service.runningStreamCount()==0);
    send(conversation);waitUntil(()->service.activeStreamCount()==0);
    assertEquals(3,attempts.get());assertNull(rows.get(conversation).getActiveRequestId());
  }
  @Test void automaticRetriesAreBoundedAndManualRecoveryStillWorks()throws Exception {
    failures.set(100);String conversation=conversation(),id=send(conversation);
    waitUntil(()->attempts.get()>=1 && service.runningStreamCount()==0);
    @SuppressWarnings("unchecked")
    var active=(Map<String,?>)ReflectionTestUtils.getField(service,"active");
    Object pending=active.values().iterator().next();
    for(int i=0;i<10;i++){
      ReflectionTestUtils.setField(pending,"retryAt",0L);
      ReflectionTestUtils.invokeMethod(service,"maintain");
    }
    assertEquals(5,attempts.get());assertEquals(1,service.activeStreamCount());
    failures.set(0);assertTrue(service.cancel(1,id));
    assertNull(rows.get(conversation).getActiveRequestId());
  }
  @Test void pendingSavesHaveBoundedAdmissionAndRecoveredSlotsCanBeReused()throws Exception {
    failures.set(100);
    for(int i=0;i<3;i++){int expected=i+1;send(conversation());waitUntil(()->attempts.get()>=expected && service.runningStreamCount()==0);}
    assertEquals(3,service.activeStreamCount());
    var error=assertThrows(org.example.traveljavaserver.common.AppException.class,()->send(conversation()));
    assertEquals("SERVICE_BUSY",error.getErrorCode());
    failures.set(0);service.cancelUser(1);
    assertEquals(0,service.activeStreamCount());
    send(conversation());waitUntil(()->service.activeStreamCount()==0);
  }
  @Test void earlyCancellationRejectsRepeatedLatePostsBeforeAnyMessageOrModelCall() {
    String conversation=conversation(),id=UUID.randomUUID().toString();
    assertTrue(service.cancel(1,id));
    for(int i=0;i<2;i++){
      var error=assertThrows(org.example.traveljavaserver.common.AppException.class,
          ()->service.chat(1,conversation,id,"迟到POST"));
      assertEquals("CHAT_CANCELLED",error.getErrorCode());
    }
    verify(store,never()).lockConversation(anyLong(),anyString());
    verify(llm,never()).newChatCall(anyList(),anyBoolean());
    assertEquals(0,service.activeStreamCount());
    var slots=(java.util.concurrent.Semaphore)ReflectionTestUtils.getField(service,"acceptedChats");
    assertEquals(3,slots.availablePermits());
  }
  @Test void earlyCancellationIsScopedToOwnerAndRequestType()throws Exception {
    String conversation=conversation(),id=UUID.randomUUID().toString();
    service.cancel(2,id);
    service.cancelPlan(1,id);
    service.chat(1,conversation,id,"正常问题");
    waitUntil(()->service.activeStreamCount()==0);
    assertEquals("completed",savedStatus);assertEquals(1,attempts.get());
  }
  @Test void expiredCancellationAllowsNewRegistration()throws Exception {
    String id=UUID.randomUUID().toString();
    service.cancel(1,id);
    @SuppressWarnings("unchecked")
    var cache=(Map<String,Long>)ReflectionTestUtils.getField(service,"cancelledRequests");
    cache.put("chat:1:"+id,System.nanoTime()-1);
    service.chat(1,conversation(),id,"正常问题");waitUntil(()->service.activeStreamCount()==0);
    assertEquals(1,attempts.get());
  }
  @Test void cancellationIntentCapacityIsBoundedAndExpiredEntriesAreReclaimed() {
    for(int i=0;i<128;i++)assertTrue(service.cancel(1,UUID.randomUUID().toString()));
    assertFalse(service.cancel(1,UUID.randomUUID().toString()));
    @SuppressWarnings("unchecked")
    var cache=(Map<String,Long>)ReflectionTestUtils.getField(service,"cancelledRequests");
    assertEquals(128,cache.size());
    cache.replaceAll((key,value)->System.nanoTime()-1);
    assertTrue(service.cancel(1,UUID.randomUUID().toString()));
    assertEquals(1,cache.size());
  }
  @Test void cancellationRecordedDuringRegistrationIsRecheckedBeforeModelExecution()throws Exception {
    String conversation=conversation(),id=UUID.randomUUID().toString();
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    when(store.lockConversation(eq(1L),eq(conversation))).thenAnswer(i->{
      entered.countDown();
      if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("registration timed out");
      return rows.get(conversation);
    });
    var executor=Executors.newSingleThreadExecutor();
    try {
      var pending=executor.submit(()->service.chat(1,conversation,id,"登记中的请求"));
      assertTrue(entered.await(2,TimeUnit.SECONDS));
      // Pause DELETE just after recording its intent, while POST still owns the session lock.
      assertEquals(Boolean.TRUE,ReflectionTestUtils.invokeMethod(service,"rememberCancellation","chat","1:"+id));
      release.countDown();pending.get(3,TimeUnit.SECONDS);
      waitUntil(()->service.activeStreamCount()==0);
      assertEquals("stopped",savedStatus);assertNull(rows.get(conversation).getActiveRequestId());
      verify(llm,never()).readStream(any(),any());
    } finally {release.countDown();executor.shutdownNow();}
  }
}
