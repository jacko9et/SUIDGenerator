# SUIDGenerator

Refer: Sonyflake

sign（1bit） - period（39bit） - instanceId（16bit） - sequence（8bit）

Can be used for about 174 years.

~(-1L << 39) / 365 / 24 / 3600 / (1000 / 10) = 174

25600 IDs can be generated per second.

(0 ~ 255) / 10ms : 256 * 100 = 25600
 
instanceId Ranges: 0 ~ 65535

(0 ~ 255).(0 ~ 255) : 256 * 256 = 65536 (0 ~ 65535)
