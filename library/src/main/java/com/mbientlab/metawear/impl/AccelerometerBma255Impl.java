/*
 * Copyright 2014-2015 MbientLab Inc. All rights reserved.
 *
 * IMPORTANT: Your use of this Software is limited to those specific rights granted under the terms of a software
 * license agreement between the user who downloaded the software, his/her employer (which must be your
 * employer) and MbientLab Inc, (the "License").  You may not use this Software unless you agree to abide by the
 * terms of the License which can be found at www.mbientlab.com/terms.  The License limits your use, and you
 * acknowledge, that the Software may be modified, copied, and distributed when used in conjunction with an
 * MbientLab Inc, product.  Other than for the foregoing purpose, you may not use, reproduce, copy, prepare
 * derivative works of, modify, distribute, perform, display or sell this Software and/or its documentation for any
 * purpose.
 *
 * YOU FURTHER ACKNOWLEDGE AND AGREE THAT THE SOFTWARE AND DOCUMENTATION ARE PROVIDED "AS IS" WITHOUT WARRANTY
 * OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION, ANY WARRANTY OF MERCHANTABILITY, TITLE,
 * NON-INFRINGEMENT AND FITNESS FOR A PARTICULAR PURPOSE. IN NO EVENT SHALL MBIENTLAB OR ITS LICENSORS BE LIABLE OR
 * OBLIGATED UNDER CONTRACT, NEGLIGENCE, STRICT LIABILITY, CONTRIBUTION, BREACH OF WARRANTY, OR OTHER LEGAL EQUITABLE
 * THEORY ANY DIRECT OR INDIRECT DAMAGES OR EXPENSES INCLUDING BUT NOT LIMITED TO ANY INCIDENTAL, SPECIAL, INDIRECT,
 * PUNITIVE OR CONSEQUENTIAL DAMAGES, LOST PROFITS OR LOST DATA, COST OF PROCUREMENT OF SUBSTITUTE GOODS, TECHNOLOGY,
 * SERVICES, OR ANY CLAIMS BY THIRD PARTIES (INCLUDING BUT NOT LIMITED TO ANY DEFENSE THEREOF), OR OTHER SIMILAR COSTS.
 *
 * Should you have any questions regarding your right to use this Software, contact MbientLab via email:
 * hello@mbientlab.com.
 */

package com.mbientlab.metawear.impl;

import com.mbientlab.metawear.AsyncDataProducer;
import com.mbientlab.metawear.Route;
import com.mbientlab.metawear.builder.RouteBuilder;
import com.mbientlab.metawear.module.AccelerometerBma255;

import java.util.Arrays;

import bolts.Task;

import static com.mbientlab.metawear.impl.ModuleId.ACCELEROMETER;

/**
 * Created by etsai on 9/1/16.
 */
class AccelerometerBma255Impl extends AccelerometerBoschImpl implements AccelerometerBma255 {
    final static byte IMPLEMENTATION= 0x3;
    private static final long serialVersionUID = -2958342250951886414L;

    private final byte[] accDataConfig= new byte[] {0x0b, 0x03};

    private transient AsyncDataProducer flat, lowhigh, motion;

    AccelerometerBma255Impl(MetaWearBoardPrivate mwPrivate) {
        super(mwPrivate);
    }

    @Override
    protected float getAccDataScale() {
        return AccRange.bitMaskToRange((byte) (accDataConfig[1] & 0xf)).scale;
    }

    @Override
    protected int getSelectedAccRange() {
        return AccRange.bitMaskToRange((byte) (accDataConfig[1] & 0xf)).ordinal();
    }

    @Override
    protected int getMaxOrientHys() {
        return 0x7;
    }

    @Override
    public Bma255ConfigEditor configure() {
        return new Bma255ConfigEditor() {
            private OutputDataRate odr= OutputDataRate.ODR_125HZ;
            private AccRange ar= AccRange.AR_2G;

            @Override
            public Bma255ConfigEditor odr(OutputDataRate odr) {
                this.odr= odr;
                return this;
            }

            @Override
            public Bma255ConfigEditor range(AccRange ar) {
                this.ar= ar;
                return this;
            }

            @Override
            public Bma255ConfigEditor odr(float odr) {
                float[] frequencies= OutputDataRate.frequencies();
                int pos= Util.closestIndex(frequencies, odr);

                return odr(OutputDataRate.values()[pos]);
            }

            @Override
            public Bma255ConfigEditor range(float fsr) {
                float[] ranges= AccRange.ranges();
                int pos= Util.closestIndex(ranges, fsr);

                return range(AccRange.values()[pos]);
            }

            @Override
            public void commit() {
                accDataConfig[0]&= 0xe0;
                accDataConfig[0]|= odr.ordinal() + 8;

                accDataConfig[1]&= 0xf0;
                accDataConfig[1]|= ar.bitmask;

                mwPrivate.sendCommand(ACCELEROMETER, DATA_CONFIG, accDataConfig);
            }
        };
    }

    @Override
    public float getOdr() {
        return OutputDataRate.values()[(accDataConfig[0] & ~0xe0) - 8].frequency;
    }

    @Override
    public float getRange() {
        return AccRange.bitMaskToRange((byte) (accDataConfig[1] & ~0xf0)).range;
    }

    private class Bma255FlatDataProducer extends BoschFlatDataProducer implements AccelerometerBma255.FlatDataProducer {
        @Override
        public ConfigEditor configure() {
            return new ConfigEditor() {
                private FlatHoldTime holdTime = FlatHoldTime.FHT_512_MS;
                private float theta = 5.6889f;

                @Override
                public ConfigEditor holdTime(FlatHoldTime time) {
                    holdTime = time;
                    return this;
                }

                @Override
                public ConfigEditor holdTime(float time) {
                    return holdTime(FlatHoldTime.values()[Util.closestIndex(FlatHoldTime.periods(), time)]);
                }

                @Override
                public ConfigEditor flatTheta(float angle) {
                    theta = angle;
                    return this;
                }

                @Override
                public void commit() {
                    writeFlatConfig(holdTime.ordinal(), theta);
                }
            };
        }
    }
    @Override
    public AccelerometerBma255.FlatDataProducer flatDetector() {
        if (flat == null) {
            flat = new Bma255FlatDataProducer();
        }
        return (AccelerometerBma255.FlatDataProducer) flat;
    }

    @Override
    public LowHighDataProducer lowHighDetector() {
        if (lowhigh == null) {
            lowhigh = new LowHighDataProducerInner(new byte[] {0x09, 0x30, (byte) 0x81, 0x0f, (byte) 0xc0});
        }
        return (LowHighDataProducer) lowhigh;
    }

    @Override
    public MotionDataProducer motionDetector() {
        if (motion == null) {
            motion = new MotionDataProducer() {
                @Override
                public NoMotionConfigEditor configureNoMotion() {
                    return new NoMotionConfigEditor() {
                        private Integer duration= null;
                        private Float threshold= null;

                        @Override
                        public NoMotionConfigEditor duration(int duration) {
                            this.duration= duration;
                            return this;
                        }

                        @Override
                        public NoMotionConfigEditor threshold(float threshold) {
                            this.threshold= threshold;
                            return this;
                        }

                        @Override
                        public void commit() {
                            byte[] motionConfig = Arrays.copyOf(DEFAULT_MOTION_CONFIG, DEFAULT_MOTION_CONFIG.length);
                            if (duration != null) {
                                motionConfig[0]&= 0x3;

                                if (duration >= 1000 && duration <= 16000) {
                                    motionConfig[0]|= ((byte) (duration / 1000 - 1000)) << 2;
                                } else if (duration >= 20000 && duration <= 80000) {
                                    motionConfig[0]|= (((byte) (duration - 20000) / 4000) << 2) | 0x40;
                                } else if (duration >= 88000 && duration <= 336000) {
                                    motionConfig[0]|= (((byte) (duration - 88000) / 8000) << 2) | 0x80;
                                }
                            }

                            if (threshold != null) {
                                motionConfig[2]= (byte) (threshold / BOSCH_NO_MOTION_THS_STEPS[getSelectedAccRange()]);
                            }

                            mwPrivate.sendCommand(ACCELEROMETER, MOTION_CONFIG, motionConfig);
                        }
                    };
                }

                @Override
                public AnyMotionConfigEditor configureAnyMotion() {
                    return new AnyMotionConfigEditorInner();
                }

                @Override
                public SlowMotionConfigEditor configureSlowMotion() {
                    return new SlowMotionConfigEditorInner();
                }

                @Override
                public Task<Route> addRoute(RouteBuilder builder) {
                    return mwPrivate.queueRouteBuilder(builder, MOTION_PRODUCER);
                }

                @Override
                public String name() {
                    return MOTION_PRODUCER;
                }

                @Override
                public void start() {
                    switch (motionType) {
                        case NO_MOTION:
                            mwPrivate.sendCommand(new byte[] {ACCELEROMETER.id, MOTION_INTERRUPT_ENABLE, (byte) 0x78, (byte) 0});
                            break;
                        case SLOW_MOTION:
                            mwPrivate.sendCommand(new byte[] {ACCELEROMETER.id, MOTION_INTERRUPT_ENABLE, (byte) 0x38, (byte) 0});
                            break;
                        case ANY_MOTION:
                            mwPrivate.sendCommand(new byte[] {ACCELEROMETER.id, MOTION_INTERRUPT_ENABLE, (byte) 0x7, (byte) 0});
                            break;
                        case SIGNIFICANT_MOTION:
                            throw new UnsupportedOperationException("Significant motion not supported on BMA255");
                    }
                }

                @Override
                public void stop() {
                    mwPrivate.sendCommand(new byte[] {ACCELEROMETER.id, MOTION_INTERRUPT_ENABLE, (byte) 0, (byte) 0x7f});
                }
            };
        }
        return (MotionDataProducer) motion;
    }
}