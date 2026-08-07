import org.openjdk.jol.info.ClassLayout;

public class MarkWordPreserve {
    public static void main(String[] args) throws Exception {
        Object o = new Object();

        System.out.println("=== 1. new 出来（未调 hashCode）===");
        System.out.println(ClassLayout.parseInstance(o).toPrintable());

        int h1 = System.identityHashCode(o);
        System.out.println("=== 2. 调用 identityHashCode 后，h1 = " + h1 + " ===");
        System.out.println(ClassLayout.parseInstance(o).toPrintable());

        // 用 wait 强制膨胀成重量级锁（wait 需要 ObjectMonitor 的 _WaitSet）
        Thread t = new Thread(() -> {
            synchronized (o) {
                System.out.println("=== 3. synchronized 块内（wait 前）===");
                System.out.println(ClassLayout.parseInstance(o).toPrintable());
                try { o.wait(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        t.start();
        t.join();

        System.out.println("=== 4. 膨胀成重量级锁后 ===");
        System.out.println(ClassLayout.parseInstance(o).toPrintable());

        int h2 = System.identityHashCode(o);
        System.out.println("=== 5. 重量级锁状态下再读 identityHashCode，h2 = " + h2 + " ===");
        System.out.println("h1 == h2 ? " + (h1 == h2));
    }
}