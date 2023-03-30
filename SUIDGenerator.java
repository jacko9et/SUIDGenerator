package com.example.generator;

import java.net.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
public final class SUIDGenerator {

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

    /**
     * Generate new id
     *
     * @return new id
     */
    public synchronized long nextId() {
        long period = getPeriod(System.currentTimeMillis());

        if (period > this.period) {
            this.period = period;
            sequence = sequence % 2 != 0 ? 0 : 1;
        } else {
            sequence = ++sequence & MAXIMUM_SEQUENCE;
            if (sequence == 0) {
                ++this.period;
                long nextTime = landmark + this.period * TIME_STEP;
                long currentTimeMillis;
                while ((currentTimeMillis = System.currentTimeMillis()) < nextTime) {
                    if (nextTime - currentTimeMillis > CLOCK_OUTLIER_THRESHOLD) {
                        throw new IllegalStateException(String.format(
                                "IPv4: %s, InstanceId: %s, NextElapsedTime: %s [%s], CurrentTime: %s [%s], ClockOutliers: %sms",
                                getIPString(getIPv4()),
                                instanceId,
                                nextTime, Instant.ofEpochMilli(nextTime),
                                currentTimeMillis, Instant.ofEpochMilli(currentTimeMillis),
                                nextTime - currentTimeMillis));
                    }
                }
            }
        }

        return (this.period << PERIOD_LEFT_SHIFT_BITS)
                | (instanceId << SEQUENCE_BITS)
                | sequence;
    }

    private long getPeriod(long currentTimeMillis) {
        long period = (currentTimeMillis - landmark) / TIME_STEP;
        if (period > MAXIMUM_PERIOD) {
            throw new IllegalStateException(String.format("Over the time limit, The last time is: %s",
                    Instant.ofEpochMilli(MAXIMUM_PERIOD * TIME_STEP + landmark).toString()));
        }
        return period;
    }

    /**
     * @param landmarkYear  A landmark year.
     * @param instanceId    Instance IDs that can work concurrently at the same time.
     * @param lastTimestamp To prevent clock rollback when the instance is not running,
     *                      provide the last elapsed time when the instance was last running.
     */
    public SUIDGenerator(int landmarkYear, long instanceId, long lastTimestamp) {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        if (landmarkYear > currentYear) {
            throw new IllegalArgumentException("The year cannot be larger than the current year.");
        }
        landmark = getLandmark(landmarkYear);
        checkInstanceId(instanceId);
        this.instanceId = instanceId;
        period = getPeriod(lastTimestamp);
    }

    private static long getLandmark(int landmarkYear) {
        return new Calendar.Builder()
                .setDate(landmarkYear, 1, 1)
                .setTimeOfDay(0, 0, 0, 0)
                .build().getTimeInMillis();
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

    private static String getIPString(long[] ip) {
        return ip[0] + "." + ip[1] + "." + ip[2] + "." + ip[3];
    }

    public static long getInstanceIdByPrivateIP() {
        long[] ip = getIPv4();
        if (!isPrivateIP(ip)) {
            throw new IllegalStateException(String.format("%s is not a private ip.", getIPString(ip)));
        }
        return (ip[2] << 8) + ip[3];
    }

    public static long resolvePeriod(long id) {
        return id >> PERIOD_LEFT_SHIFT_BITS & ~(-1L << PERIOD_BITS);
    }

    public static Instant resolveInstant(int landmarkYear, long id) {
        return Instant.ofEpochMilli(getLandmark(landmarkYear) + resolvePeriod(id) * TIME_STEP);
    }

    public static LocalDateTime resolveLocalDateTime(int landmarkYear, long id) {
        return LocalDateTime.ofInstant(resolveInstant(landmarkYear, id), ZoneId.systemDefault());
    }

    public static long resolveInstanceId(long id) {
        return id >> SEQUENCE_BITS & ~(-1L << INSTANCE_ID_BITS);
    }

    public static String resolveLowerIPv4(long instanceId) {
        return (instanceId >> 8) + "." + (instanceId & 255);
    }

    public static long resolveSequence(long id) {
        return id & ~(-1L << SEQUENCE_BITS);
    }

    public static void main(String[] args) {
        SUIDGenerator suidGenerator = new SUIDGenerator(
                2022,
                SUIDGenerator.getInstanceIdByPrivateIP(),
                System.currentTimeMillis());
        long id = suidGenerator.nextId();
        System.out.println(id);
        System.out.println(resolveLocalDateTime(2022, id));
        System.out.println(resolveLowerIPv4(resolveInstanceId(id)));
        System.out.println(resolveInstanceId(id));
        System.out.println(resolveSequence(id));
    }

}
