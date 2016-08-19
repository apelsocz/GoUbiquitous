/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    private static final String LOG_TAG = SunshineWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private static final Typeface ITALIC_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC);


    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        private static final String WEATHER_PATH = "/weather";
        private static final String WEATHER_INFO_PATH = "/weather-info";

        private static final String KEY_UUID = "uuid";
        private static final String KEY_HIGH = "high";
        private static final String KEY_LOW = "low";
        private static final String KEY_WEATHER_ID = "weatherId";

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mDividerPaint;
        Paint mHighPaint;
        Paint mLowPaint;

        boolean mAmbient;
        Calendar mCalendar;
        int mTapCount;
        String mTempHigh;
        String mTempLow;

        float mXOffsetTime;
        float mYOffsetTime;
        float mXOffsetDate;
        float mYOffsetDate;
        float mXOffsetHigh;
        float mYOffsetHigh;
        float mXOffsetLow;
        float mXOffsetIcon;
        float mYOffsetLow;
        float mYOffsetTemp;
        float mXOffsetDividerStart;
        float mXOffsetDividerEnd;
        float mYOffsetDivider;

        Bitmap mWeatherIcon;
        Boolean mDrawIcon;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        boolean mRegisteredTimeZoneReceiver = false;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeInMillis(System.currentTimeMillis());
                mCalendar.setTimeZone(TimeZone.getDefault());
            }
        };

        GoogleApiClient mAPiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = SunshineWatchFace.this.getResources();

            mYOffsetTime = resources.getDimension(R.dimen.digital_y_offset_time);
            mYOffsetDate = resources.getDimension(R.dimen.digital_y_offset_date);
            mXOffsetDividerStart = resources.getDimension(R.dimen.digital_x_offset_divider_start);
            mXOffsetDividerEnd = resources.getDimension(R.dimen.digital_x_offset_divider_end);
            mYOffsetDivider = resources.getDimension(R.dimen.digital_y_offset_divider);
            mYOffsetTemp = resources.getDimension(R.dimen.digital_y_offset_temp);

            mDrawIcon = true;

            mTempHigh = "";
            mTempLow = "";

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTimePaint = new Paint();
            mTimePaint = createPrimaryTextPaint(resources.getColor(R.color.primary_color), BOLD_TYPEFACE);

            mDatePaint = new Paint();
            mDatePaint = createSecondaryTextPaint(resources.getColor(R.color.secondary_color), ITALIC_TYPEFACE);

            mDividerPaint = new Paint();
            mDividerPaint = createPaint(resources.getColor(R.color.divider));

            mHighPaint = new Paint();
            mHighPaint = createPrimaryTextPaint(resources.getColor(R.color.primary_color), NORMAL_TYPEFACE);

            mLowPaint = new Paint();
            mLowPaint = createSecondaryTextPaint(resources.getColor(R.color.secondary_color), NORMAL_TYPEFACE);

            Drawable weatherDrawable = resources.getDrawable(R.drawable.ic_status);
            mWeatherIcon = ((BitmapDrawable) weatherDrawable).getBitmap();

            mCalendar = Calendar.getInstance();

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            releaseGoogleApiClient();
            super.onDestroy();
        }

        private Paint createPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createPrimaryTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createSecondaryTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mAPiClient.connect();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeInMillis(System.currentTimeMillis());
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();
                releaseGoogleApiClient();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        private void releaseGoogleApiClient() {
            if (mAPiClient != null && mAPiClient.isConnected()) {
                Wearable.DataApi.removeListener(mAPiClient, this);
                mAPiClient.disconnect();
            }
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffsetTime = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_time_round : R.dimen.digital_x_offset_time);
            mXOffsetDate = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_date_round : R.dimen.digital_x_offset_date);

            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_time_round : R.dimen.digital_text_size_time);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_date_round : R.dimen.digital_text_size_date);
            float tempTextSize = resources.getDimension(isRound
                    ?R.dimen.digital_text_size_temp_round : R.dimen.digital_text_size_temp);

            mTimePaint.setTextSize(timeTextSize);
            mDatePaint.setTextSize(dateTextSize);
            mHighPaint.setTextSize(tempTextSize);
            mLowPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mHighPaint.setAntiAlias(!inAmbientMode);
                    mLowPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    mDividerPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.divider : R.color.secondary_color));
                    mDrawIcon = mTapCount % 2 == 0;
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mCalendar.setTimeInMillis(System.currentTimeMillis());
            Date date = mCalendar.getTime();
            SimpleDateFormat timeFormat = new SimpleDateFormat(
                    DateFormat.is24HourFormat(getApplicationContext()) ? "HH:mm" : "hh:mm",
                    Locale.getDefault());
            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault());

            mXOffsetTime = bounds.centerX() - mTimePaint.measureText(timeFormat.format(date))/2f;
            mXOffsetDate = bounds.centerX() - mDatePaint.measureText(dateFormat.format(date))/2f;
            mXOffsetDividerStart = bounds.centerX() - mHighPaint.measureText(timeFormat.format(date))/3f;
            mXOffsetDividerEnd = bounds.centerX() + mLowPaint.measureText(timeFormat.format(date))/3f;
            mXOffsetHigh = bounds.centerX() - mHighPaint.measureText(mTempHigh)/2f;
            mXOffsetLow = bounds.centerX() + bounds.width()/5f;
            mXOffsetIcon = bounds.centerX() - bounds.width()/2.5f;

            canvas.drawText(timeFormat.format(date), mXOffsetTime, mYOffsetTime, mTimePaint);
            canvas.drawText(dateFormat.format(date), mXOffsetDate, mYOffsetDate, mDatePaint);

            canvas.drawLine(mXOffsetDividerStart, mYOffsetDivider, mXOffsetDividerEnd,
                    mYOffsetDivider, mDividerPaint);

            canvas.drawText(mTempHigh, mXOffsetHigh, mYOffsetTemp, mHighPaint);
            canvas.drawText(mTempLow, mXOffsetLow, mYOffsetTemp, mLowPaint);

            if (mDrawIcon) {
                canvas.drawBitmap(mWeatherIcon, mXOffsetIcon,
                        mYOffsetTemp - mHighPaint.getTextSize(), null);
            }

        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mAPiClient, this);
            getWeatherData();
        }

        /**
         * Requests the weather data from the app's {@code MainActivity}
         */
        public void getWeatherData() {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_PATH);
            putDataMapRequest.getDataMap().putString(KEY_UUID, UUID.randomUUID().toString());
            PutDataRequest request = putDataMapRequest.asPutDataRequest()
                    .setUrgent();

            Wearable.DataApi.putDataItem(mAPiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(LOG_TAG, "Failed asking phone for weather data");

                            } else {
                                Log.d(LOG_TAG, "Successfully asked for weather data");
                            }
                        }
                    });
        }

        @Override
        public void onConnectionSuspended(int i) {}

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.i("onDataChanged", dataEventBuffer.toString());
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    Log.i(LOG_TAG, path);
                    if (path.equals(WEATHER_INFO_PATH)) {
                        if (dataMap.containsKey(KEY_HIGH)) {
                            mTempHigh = dataMap.getString(KEY_HIGH);
                        }
                        else {
                            Log.d(SunshineWatchFace.LOG_TAG, "What? No high?");
                        }

                        if (dataMap.containsKey(KEY_LOW)) {
                            mTempLow = dataMap.getString(KEY_LOW);
                        }
                        else {
                            Log.d(SunshineWatchFace.LOG_TAG, "What? No low?");
                        }

                        if (dataMap.containsKey(KEY_WEATHER_ID)) {
                            int weatherId = dataMap.getInt(KEY_WEATHER_ID);
                            Drawable bmp = getResources().getDrawable(Utility.getIconResourceForWeatherCondition(weatherId));
                            Bitmap icon = ((BitmapDrawable) bmp).getBitmap();
                            mWeatherIcon = Bitmap.createBitmap(icon);
                        }
                        else {
                            Log.d(SunshineWatchFace.LOG_TAG, "What? no weatherId?");
                        }

                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {}
    }
}
