/*
 *  Copyright 2015 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package com.pine.rtc.org.component;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simple CPU monitor.  The caller creates a CpuMonitor object which can then
 * be used via sampleCpuUtilization() to collect the percentual use of the
 * cumulative CPU capacity for all CPUs running at their nominal frequency.  3
 * values are generated: (1) getCpuCurrent() returns the use since the last
 * sampleCpuUtilization(), (2) getCpuAvg3() returns the use since 3 prior
 * calls, and (3) getCpuAvgAll() returns the use over all SAMPLE_SAVE_NUMBER
 * calls.
 * <p>
 * <p>CPUs in Android are often "offline", and while this of course means 0 Hz
 * as current frequency, in this state we cannot even get their nominal
 * frequency.  We therefore tread carefully, and allow any CPU to be missing.
 * Missing CPUs are assumed to have the same nominal frequency as any close
 * lower-numbered CPU, but as soon as it is online, we'll get their proper
 * frequency and remember it.  (Since CPU 0 in practice always seem to be
 * online, this unidirectional frequency inheritance should be no problem in
 * practice.)
 * <p>
 * <p>Caveats:
 * o No provision made for zany "turbo" mode, common in the x86 world.
 * o No provision made for ARM big.LITTLE; if CPU n can switch behind our
 * back, we might get incorrect estimates.
 * o This is not thread-safe.  To call asynchronously, create different
 * CpuMonitor objects.
 * <p>
 * <p>If we can gather enough info to generate a sensible result,
 * sampleCpuUtilization returns true.  It is designed to never throw an
 * exception.
 * <p>
 * <p>sampleCpuUtilization should not be called too often in its present form,
 * since then deltas would be small and the percent values would fluctuate and
 * be unreadable. If it is desirable to call it more often than say once per
 * second, one would need to increase SAMPLE_SAVE_NUMBER and probably use
 * Queue<Integer> to avoid copying overhead.
 * <p>
 * <p>Known problems:
 * 1. Nexus 7 devices running Kitkat have a kernel which often output an
 * incorrect 'idle' field in /proc/stat.  The value is close to twice the
 * correct value, and then returns to back to correct reading.  Both when
 * jumping up and back down we might create faulty CPU load readings.
 */

public class CpuMonitor {
    private static final String TAG = "CpuMonitor";

    private static final int MOVING_AVERAGE_SAMPLES = 5;

    private static final int CPU_STAT_SAMPLE_PERIOD_MS = 2000;
    private static final int CPU_STAT_LOG_PERIOD_MS = 6000;

    private final Context mAppContext;
    // User CPU usage at current frequency.
    private final MovingAverage mUserCpuUsage;
    // System CPU usage at current frequency.
    private final MovingAverage mSystemCpuUsage;
    // Total CPU usage relative to maximum frequency.
    private final MovingAverage mTotalCpuUsage;
    // CPU frequency in percentage from maximum.
    private final MovingAverage mFrequencyScale;

    private ScheduledExecutorService mExecutor;
    private long mLastStatLogTimeMs;
    private long[] mCpuFreqMax;
    private int mCpusPresent;
    private int mActualCpusPresent;
    private boolean mInitialized;
    private boolean mCpuOveruse;
    private String[] mMaxPath;
    private String[] mCurPath;
    private double[] mCurFreqScales;
    private ProcStat mLastProcStat;

    public CpuMonitor(Context context) {
        Log.d(TAG, "CpuMonitor ctor.");
        mAppContext = context.getApplicationContext();
        mUserCpuUsage = new MovingAverage(MOVING_AVERAGE_SAMPLES);
        mSystemCpuUsage = new MovingAverage(MOVING_AVERAGE_SAMPLES);
        mTotalCpuUsage = new MovingAverage(MOVING_AVERAGE_SAMPLES);
        mFrequencyScale = new MovingAverage(MOVING_AVERAGE_SAMPLES);
        mLastStatLogTimeMs = SystemClock.elapsedRealtime();

        scheduleCpuUtilizationTask();
    }

    private static long parseLong(String value) {
        long number = 0;
        try {
            number = Long.parseLong(value);
        } catch (NumberFormatException e) {
            Log.e(TAG, "parseLong error.", e);
        }
        return number;
    }

    public void pause() {
        if (mExecutor != null) {
            Log.d(TAG, "pause");
            mExecutor.shutdownNow();
            mExecutor = null;
        }
    }

    public void resume() {
        Log.d(TAG, "resume");
        resetStat();
        scheduleCpuUtilizationTask();
    }

    public synchronized void reset() {
        if (mExecutor != null) {
            Log.d(TAG, "reset");
            resetStat();
            mCpuOveruse = false;
        }
    }

    public synchronized int getCpuUsageCurrent() {
        return doubleToPercent(mUserCpuUsage.getCurrent() + mSystemCpuUsage.getCurrent());
    }

    public synchronized int getCpuUsageAverage() {
        return doubleToPercent(mUserCpuUsage.getAverage() + mSystemCpuUsage.getAverage());
    }

    public synchronized int getFrequencyScaleAverage() {
        return doubleToPercent(mFrequencyScale.getAverage());
    }

    private void scheduleCpuUtilizationTask() {
        if (mExecutor != null) {
            mExecutor.shutdownNow();
            mExecutor = null;
        }

        mExecutor = Executors.newSingleThreadScheduledExecutor();
        @SuppressWarnings("unused") // Prevent downstream linter warnings.
                Future<?> possiblyIgnoredError = mExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                cpuUtilizationTask();
            }
        }, 0, CPU_STAT_SAMPLE_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private void cpuUtilizationTask() {
        boolean cpuMonitorAvailable = sampleCpuUtilization();
        if (cpuMonitorAvailable
                && SystemClock.elapsedRealtime() - mLastStatLogTimeMs >= CPU_STAT_LOG_PERIOD_MS) {
            mLastStatLogTimeMs = SystemClock.elapsedRealtime();
            String statString = getStatString();
            Log.d(TAG, statString);
        }
    }

    private void init() {
        try {
            FileReader fin = new FileReader("/sys/devices/system/cpu/present");
            try {
                BufferedReader reader = new BufferedReader(fin);
                Scanner scanner = new Scanner(reader).useDelimiter("[-\n]");
                scanner.nextInt(); // Skip leading number 0.
                mCpusPresent = 1 + scanner.nextInt();
                scanner.close();
            } catch (Exception e) {
                Log.e(TAG, "Cannot do CPU stats due to /sys/devices/system/cpu/present parsing problem");
            } finally {
                fin.close();
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Cannot do CPU stats since /sys/devices/system/cpu/present is missing");
        } catch (IOException e) {
            Log.e(TAG, "Error closing file");
        }

        mCpuFreqMax = new long[mCpusPresent];
        mMaxPath = new String[mCpusPresent];
        mCurPath = new String[mCpusPresent];
        mCurFreqScales = new double[mCpusPresent];
        for (int i = 0; i < mCpusPresent; i++) {
            mCpuFreqMax[i] = 0; // Frequency "not yet determined".
            mCurFreqScales[i] = 0;
            mMaxPath[i] = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq";
            mCurPath[i] = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq";
        }

        mLastProcStat = new ProcStat(0, 0, 0);
        resetStat();

        mInitialized = true;
    }

    private synchronized void resetStat() {
        mUserCpuUsage.reset();
        mSystemCpuUsage.reset();
        mTotalCpuUsage.reset();
        mFrequencyScale.reset();
        mLastStatLogTimeMs = SystemClock.elapsedRealtime();
    }

    private int getBatteryLevel() {
        // Use sticky broadcast with null receiver to read battery level once only.
        Intent intent = mAppContext.registerReceiver(
                null /* receiver */, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        int batteryLevel = 0;
        int batteryScale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (batteryScale > 0) {
            batteryLevel =
                    (int) (100f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) / batteryScale);
        }
        return batteryLevel;
    }

    /**
     * Re-measure CPU use.  Call this method at an interval of around 1/s.
     * This method returns true on success.  The fields
     * cpuCurrent, cpuAvg3, and cpuAvgAll are updated on success, and represents:
     * cpuCurrent: The CPU use since the last sampleCpuUtilization call.
     * cpuAvg3: The average CPU over the last 3 calls.
     * cpuAvgAll: The average CPU over the last SAMPLE_SAVE_NUMBER calls.
     */
    private synchronized boolean sampleCpuUtilization() {
        long lastSeenMaxFreq = 0;
        long cpuFreqCurSum = 0;
        long cpuFreqMaxSum = 0;

        if (!mInitialized) {
            init();
        }
        if (mCpusPresent == 0) {
            return false;
        }

        mActualCpusPresent = 0;
        for (int i = 0; i < mCpusPresent; i++) {
            /*
             * For each CPU, attempt to first read its max frequency, then its
             * current frequency.  Once as the max frequency for a CPU is found,
             * save it in mCpuFreqMax[].
             */

            mCurFreqScales[i] = 0;
            if (mCpuFreqMax[i] == 0) {
                // We have never found this CPU's max frequency.  Attempt to read it.
                long cpufreqMax = readFreqFromFile(mMaxPath[i]);
                if (cpufreqMax > 0) {
                    Log.d(TAG, "Core " + i + ". Max frequency: " + cpufreqMax);
                    lastSeenMaxFreq = cpufreqMax;
                    mCpuFreqMax[i] = cpufreqMax;
                    mMaxPath[i] = null; // Kill path to free its memory.
                }
            } else {
                lastSeenMaxFreq = mCpuFreqMax[i]; // A valid, previously read value.
            }

            long cpuFreqCur = readFreqFromFile(mCurPath[i]);
            if (cpuFreqCur == 0 && lastSeenMaxFreq == 0) {
                // No current frequency information for this CPU core - ignore it.
                continue;
            }
            if (cpuFreqCur > 0) {
                mActualCpusPresent++;
            }
            cpuFreqCurSum += cpuFreqCur;

            /* Here, lastSeenMaxFreq might come from
             * 1. cpuFreq[i], or
             * 2. a previous iteration, or
             * 3. a newly read value, or
             * 4. hypothetically from the pre-loop dummy.
             */
            cpuFreqMaxSum += lastSeenMaxFreq;
            if (lastSeenMaxFreq > 0) {
                mCurFreqScales[i] = (double) cpuFreqCur / lastSeenMaxFreq;
            }
        }

        if (cpuFreqCurSum == 0 || cpuFreqMaxSum == 0) {
            Log.e(TAG, "Could not read max or current frequency for any CPU");
            return false;
        }

        /*
         * Since the cycle counts are for the period between the last invocation
         * and this present one, we average the percentual CPU frequencies between
         * now and the beginning of the measurement period.  This is significantly
         * incorrect only if the frequencies have peeked or dropped in between the
         * invocations.
         */
        double currentFrequencyScale = cpuFreqCurSum / (double) cpuFreqMaxSum;
        if (mFrequencyScale.getCurrent() > 0) {
            currentFrequencyScale = (mFrequencyScale.getCurrent() + currentFrequencyScale) * 0.5;
        }

        ProcStat procStat = readProcStat();
        if (procStat == null) {
            return false;
        }

        long diffUserTime = procStat.userTime - mLastProcStat.userTime;
        long diffSystemTime = procStat.systemTime - mLastProcStat.systemTime;
        long diffIdleTime = procStat.idleTime - mLastProcStat.idleTime;
        long allTime = diffUserTime + diffSystemTime + diffIdleTime;

        if (currentFrequencyScale == 0 || allTime == 0) {
            return false;
        }

        // Update statistics.
        mFrequencyScale.addValue(currentFrequencyScale);

        double currentUserCpuUsage = diffUserTime / (double) allTime;
        mUserCpuUsage.addValue(currentUserCpuUsage);

        double currentSystemCpuUsage = diffSystemTime / (double) allTime;
        mSystemCpuUsage.addValue(currentSystemCpuUsage);

        double currentTotalCpuUsage =
                (currentUserCpuUsage + currentSystemCpuUsage) * currentFrequencyScale;
        mTotalCpuUsage.addValue(currentTotalCpuUsage);

        // Save new measurements for next round's deltas.
        mLastProcStat = procStat;

        return true;
    }

    private int doubleToPercent(double d) {
        return (int) (d * 100 + 0.5);
    }

    private synchronized String getStatString() {
        StringBuilder stat = new StringBuilder();
        stat.append("CPU User: ")
                .append(doubleToPercent(mUserCpuUsage.getCurrent()))
                .append("/")
                .append(doubleToPercent(mUserCpuUsage.getAverage()))
                .append(". System: ")
                .append(doubleToPercent(mSystemCpuUsage.getCurrent()))
                .append("/")
                .append(doubleToPercent(mSystemCpuUsage.getAverage()))
                .append(". Freq: ")
                .append(doubleToPercent(mFrequencyScale.getCurrent()))
                .append("/")
                .append(doubleToPercent(mFrequencyScale.getAverage()))
                .append(". Total usage: ")
                .append(doubleToPercent(mTotalCpuUsage.getCurrent()))
                .append("/")
                .append(doubleToPercent(mTotalCpuUsage.getAverage()))
                .append(". Cores: ")
                .append(mActualCpusPresent);
        stat.append("( ");
        for (int i = 0; i < mCpusPresent; i++) {
            stat.append(doubleToPercent(mCurFreqScales[i])).append(" ");
        }
        stat.append("). Battery: ").append(getBatteryLevel());
        if (mCpuOveruse) {
            stat.append(". Overuse.");
        }
        return stat.toString();
    }

    /**
     * Read a single integer value from the named file.  Return the read value
     * or if an error occurs return 0.
     */
    private long readFreqFromFile(String fileName) {
        long number = 0;
        try {
            BufferedReader reader = new BufferedReader(new FileReader(fileName));
            try {
                String line = reader.readLine();
                number = parseLong(line);
            } finally {
                reader.close();
            }
        } catch (FileNotFoundException e) {
            // CPU core is off, so file with its scaling frequency .../cpufreq/scaling_cur_freq
            // is not present. This is not an error.
        } catch (IOException e) {
            // CPU core is off, so file with its scaling frequency .../cpufreq/scaling_cur_freq
            // is empty. This is not an error.
        }
        return number;
    }

    /*
     * Read the current utilization of all CPUs using the cumulative first line
     * of /proc/stat.
     */
    private ProcStat readProcStat() {
        long userTime = 0;
        long systemTime = 0;
        long idleTime = 0;
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"));
            try {
                // line should contain something like this:
                // cpu  5093818 271838 3512830 165934119 101374 447076 272086 0 0 0
                //       user    nice  system     idle   iowait  irq   softirq
                String line = reader.readLine();
                String[] lines = line.split("\\s+");
                int length = lines.length;
                if (length >= 5) {
                    userTime = parseLong(lines[1]); // user
                    userTime += parseLong(lines[2]); // nice
                    systemTime = parseLong(lines[3]); // system
                    idleTime = parseLong(lines[4]); // idle
                }
                if (length >= 8) {
                    userTime += parseLong(lines[5]); // iowait
                    systemTime += parseLong(lines[6]); // irq
                    systemTime += parseLong(lines[7]); // softirq
                }
            } catch (Exception e) {
                Log.e(TAG, "Problems parsing /proc/stat", e);
                return null;
            } finally {
                reader.close();
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Cannot open /proc/stat for reading", e);
            return null;
        } catch (IOException e) {
            Log.e(TAG, "Problems reading /proc/stat", e);
            return null;
        }
        return new ProcStat(userTime, systemTime, idleTime);
    }

    private static class ProcStat {
        final long userTime;
        final long systemTime;
        final long idleTime;

        ProcStat(long userTime, long systemTime, long idleTime) {
            this.userTime = userTime;
            this.systemTime = systemTime;
            this.idleTime = idleTime;
        }
    }

    private static class MovingAverage {
        private final int size;
        private double sum;
        private double currentValue;
        private double[] circBuffer;
        private int circBufferIndex;

        public MovingAverage(int size) {
            if (size <= 0) {
                throw new AssertionError("Size value in MovingAverage ctor should be positive.");
            }
            this.size = size;
            circBuffer = new double[size];
        }

        public void reset() {
            Arrays.fill(circBuffer, 0);
            circBufferIndex = 0;
            sum = 0;
            currentValue = 0;
        }

        public void addValue(double value) {
            sum -= circBuffer[circBufferIndex];
            circBuffer[circBufferIndex++] = value;
            currentValue = value;
            sum += value;
            if (circBufferIndex >= size) {
                circBufferIndex = 0;
            }
        }

        public double getCurrent() {
            return currentValue;
        }

        public double getAverage() {
            return sum / (double) size;
        }
    }
}
