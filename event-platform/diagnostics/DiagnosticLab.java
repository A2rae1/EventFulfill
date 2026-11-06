import java.util.*;
import java.util.concurrent.*;
public class DiagnosticLab {
 public static void main(String[] args)throws Exception{
  var release=new CountDownLatch(1);var barrier=new CyclicBarrier(2);Object a=new Object(),b=new Object();
  Thread first=new Thread(()->lock(a,b,barrier),"lab-deadlock-first"),second=new Thread(()->lock(b,a,barrier),"lab-deadlock-second");first.setDaemon(true);second.setDaemon(true);first.start();second.start();
  var pool=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->new Thread(r,"lab-bounded-worker"),new ThreadPoolExecutor.AbortPolicy());
  pool.execute(()->{try{release.await();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});pool.execute(()->{});
  try{pool.execute(()->{});throw new AssertionError("Expected rejection");}catch(RejectedExecutionException expected){System.out.println("BOUNDED_REJECTION_OBSERVED");}
  System.out.println("READY "+ProcessHandle.current().pid());System.out.flush();
  try{Thread.sleep(20000);}finally{release.countDown();pool.shutdownNow();}
 }
 static void lock(Object first,Object second,CyclicBarrier barrier){synchronized(first){try{barrier.await();}catch(Exception e){throw new RuntimeException(e);}synchronized(second){throw new AssertionError("Intentional deadlock did not form");}}}
 public static class ExpectedOOM {
  public static void main(String[] args){var kept=new ArrayList<byte[]>();try{while(true)kept.add(new byte[1024*1024]);}catch(OutOfMemoryError expected){System.out.println("LAB_EXPECTED_OOM");}}
 }
}
