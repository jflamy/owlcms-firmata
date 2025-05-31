package timing;

public class TimingTest {

	private static final int NB_TIMES = 999;

	public static void mainX(String[] args) {
		long[] times = new long[NB_TIMES];

		for (int i = 0; i < NB_TIMES; i++) {
//			try {
				Thread.yield();
				times[i] = System.nanoTime();
//			}
//			catch (InterruptedException e) {
//				e.printStackTrace();
//			}
		}
		
		long sum = 0;
		for (int i = 1; i < NB_TIMES; i++) {
			long l = times[i] - times[i-1];
			System.out.println(l);
			sum = sum + l;
		}
		System.out.println(sum*1.0D/NB_TIMES-1);
	}

}
