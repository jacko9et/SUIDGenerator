package com.example.generator;

import java.net.*;
import java.time.Instant;
import java.util.Calendar;

/**
 * SUID Generator
 * <pre>
 *     Refer: Sonyflake
 *     sign（1bit） - period（39bit） - instanceId（16bit） - sequence（8bit）
 *
 *     Can be used for about 174 years.
 *     ~(-1L << 39) / 365 / 24 / 3600 / (1000 / 10) = 174
 *
 *     25600 IDs can be generated per second.
 *     (0 ~ 255) / 10ms : 256 * 100 = 25600
 *
 *     instanceId Ranges: 0 ~ 65535
 *     (0 ~ 255).(0 ~ 255) : 256 * 256 = 65536 (0 ~ 65535)
 * </pre>
 *
 * @author Jack
 * @since 1.0
 */
public class SUIDGenerator {
    
    private static final long INSTANCE_ID_BITS = Long.toUnsignedString(255 * 255, 2).length();
    private static final long SEQUENCE_BITS = Long.toUnsignedString(255, 2).length();
    private static final long PERIOD_BITS = Long.SIZE - INSTANCE_ID_BITS - SEQUENCE_BITS - 1;
    private static final long MAXIMUM_PERIOD = ~(-1L << PERIOD_BITS);
    private static final long MAXIMUM_INSTANCE_ID = ~(-1L << INSTANCE_ID_BITS);
    private static final long MAXIMUM_SEQUENCE = ~(-1L << SEQUENCE_BITS);
    private static final long PERIOD_LEFT_SHIFT_BITS = INSTANCE_ID_BITS + SEQUENCE_BITS;
    private static final long TIME_STEP = 10L;
    private static final long CLOCK_OUTLIER_THRESHOLD = 2000L;

    private final long landmark;
    private final long instanceId;
    private long period;
    private long sequence;

    public synchronized long nextId() {
        long period = getPeriod(System.currentTimeMillis());

        if (period > this.period) {
            this.period = period;
            this.sequence = this.sequence % 2 != 0 ? 0 : 1;
        } else {
            this.sequence = ++this.sequence & MAXIMUM_SEQUENCE;
            if (this.sequence == 0) {
                ++this.period;
                long nextTime = this.landmark + this.period * TIME_STEP;
                long currentTimeMillis;
                while ((currentTimeMillis = System.currentTimeMillis()) < nextTime) {
                    if (nextTime - currentTimeMillis > CLOCK_OUTLIER_THRESHOLD) {
                        throw new RuntimeException(String.format(
                                "LowerIPv4: %s, InstanceId: %s, NextElapsedTime: %s [%s], CurrentTime: %s [%s], ClockOutliers: %sms",
                                getLowerIPv4ByInstanceId(instanceId),
                                this.instanceId, nextTime, Instant.ofEpochMilli(nextTime),
                                currentTimeMillis, Instant.ofEpochMilli(currentTimeMillis),
                                nextTime - currentTimeMillis));
                    }
                }
            }
        }

        return (this.period << PERIOD_LEFT_SHIFT_BITS)
                | (this.instanceId << SEQUENCE_BITS)
                | this.sequence;
    }

    private long getPeriod(long currentTimeMillis) {
        long period = (currentTimeMillis - this.landmark) / TIME_STEP;
        if (period > MAXIMUM_PERIOD) {
            throw new RuntimeException(String.format("Over the time limit, The last time is: %s",
                    Instant.ofEpochMilli(MAXIMUM_PERIOD * TIME_STEP + this.landmark).toString()));
        }
        return period;
    }

    private static final int DEFAULT_LANDMARK_YEAR = 2022;

    public SUIDGenerator() {
        this(DEFAULT_LANDMARK_YEAR);
    }

    public SUIDGenerator(int year) {
        this(year, getInstanceIdByPrivateIP(), 0);
    }

    public SUIDGenerator(long instanceId) {
        this(DEFAULT_LANDMARK_YEAR, instanceId, 0);
    }

    /**
     * @param year          A landmark year.
     * @param instanceId    Instance IDs that can work concurrently at the same time.
     * @param lastTimestamp To prevent clock rollback when the instance is not running,
     *                      provide the last elapsed time when the instance was last running.
     */
    public SUIDGenerator(int year, long instanceId, long lastTimestamp) {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        int landmarkYear = DEFAULT_LANDMARK_YEAR;
        if (year <= currentYear && year > landmarkYear) {
            landmarkYear = year;
        }
        this.landmark = new Calendar.Builder()
                .setDate(landmarkYear, 1, 1)
                .setTimeOfDay(0, 0, 0, 0)
                .build().getTimeInMillis();

        checkInstanceId(instanceId);
        this.instanceId = instanceId;
        this.period = getPeriod(lastTimestamp);
    }

    private static void checkInstanceId(long instanceId) {
        if (instanceId > MAXIMUM_INSTANCE_ID || instanceId < 0) {
            throw new IllegalArgumentException(String.format(
                    "The instanceId: %s is not in the valid value range (0 ~ %d).",
                    instanceId, MAXIMUM_INSTANCE_ID));
        }
    }

    private static boolean isPrivateIP(long[] ip) {
        return ip != null && (ip[0] == 10 || ip[0] == 172 && (ip[1] >= 16 && ip[1] < 32) || ip[0] == 192 && ip[1] == 168);
    }

    private static long[] getIPv4() {
        try {
            byte[] address = InetAddress.getLocalHost().getAddress();
            if ((address[0] & 0xff) == 127) {
                try (final DatagramSocket socket = new DatagramSocket()) {
                    socket.connect(InetAddress.getByName("8.8.8.8"), 10005);
                    if (socket.getLocalAddress() instanceof Inet4Address) {
                        address = socket.getLocalAddress().getAddress();
                    }
                }
            }
            return new long[]{address[0] & 0xff, address[1] & 0xff, address[2] & 0xff, address[3] & 0xff};
        } catch (final UnknownHostException | SocketException e) {
            throw new IllegalStateException("Cannot get Local Address, please check your network!");
        }
    }

    public static long getInstanceIdByPrivateIP() {
        long[] ip = getIPv4();
        if (!isPrivateIP(ip)) {
            StringBuilder builder = new StringBuilder();
            builder.append(ip[0]).append(".")
                    .append(ip[1]).append(".")
                    .append(ip[2]).append(".")
                    .append(ip[3]);
            throw new RuntimeException(String.format("%s is not a private ip.", builder));
        }
        return (ip[2] << 8) + ip[3];
    }

    public static String getLowerIPv4ByInstanceId(long instanceId) {
        return (instanceId >> 8) + "." + (instanceId & 255);
    }

    public static void main(String[] args) {
        System.out.println(getInstanceIdByPrivateIP());
        SUIDGenerator suidGenerator = new SUIDGenerator();
        System.out.println(getLowerIPv4ByInstanceId(suidGenerator.instanceId));
        for (int i = 0; i < 10; i++) {
            System.out.println(suidGenerator.nextId());
        }
        System.out.println("=================================");
        suidGenerator = new SUIDGenerator(0L);
        for (int i = 0; i < 10; i++) {
            System.out.println(suidGenerator.nextId());
        }
    }

}
