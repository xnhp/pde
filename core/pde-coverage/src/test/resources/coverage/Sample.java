public class Sample {
    public int add(final int a, final int b) {
        return a + b;
    }

    public int unused(final int x) {
        return x * 2;
    }

    public static void main(final String[] args) {
        System.out.println(new Sample().add(2, 3));
    }
}
