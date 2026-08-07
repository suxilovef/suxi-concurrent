import org.openjdk.jol.info.ClassLayout;

public class JolByteOrder {
    public static void main(String[] args) throws Exception {
        Object o = new Object();
        System.out.println("=== new Object() ===");
        System.out.println(ClassLayout.parseInstance(o).toPrintable());
        o.hashCode();
        System.out.println("=== after hashCode() ===");
        System.out.println(ClassLayout.parseInstance(o).toPrintable());
    }
}