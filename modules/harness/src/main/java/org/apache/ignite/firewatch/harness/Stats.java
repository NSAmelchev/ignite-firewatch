package org.apache.ignite.firewatch.harness;

import java.util.List;

/** Small descriptive statistics helpers. */
public final class Stats {
    /** Summary of a series of numbers. */
    public static final class Summary {
        /** */
        public double mean;

        /** */
        public double stdev;

        /** */
        public double min;

        /** */
        public double max;

        /** */
        public double total;

        /** */
        public int samples;
    }

    /**
     * Summarizes a series of numbers.
     *
     * @param xs Samples.
     * @return Summary of the samples.
     */
    public static Summary summary(List<Double> xs) {
        Summary s = new Summary();
        s.samples = xs.size();

        if (xs.isEmpty())
            return s;

        double sum = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;

        for (double x : xs) {
            sum += x;
            min = Math.min(min, x);
            max = Math.max(max, x);
        }

        s.total = sum;
        s.mean = sum / xs.size();
        s.min = min;
        s.max = max;

        if (xs.size() > 1) {
            double ss = 0;

            for (double x : xs)
                ss += (x - s.mean) * (x - s.mean);

            s.stdev = Math.sqrt(ss / (xs.size() - 1));
        }

        return s;
    }

    /** */
    private Stats() {
    }
}
