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

package pe.asomapps.udacity.goubiquitous;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.DateFormatSymbols;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WeatherWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

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
        private final WeakReference<WeatherWatchFace.Engine> mWeakReference;

        public EngineHandler(WeatherWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        boolean isRound, mAmbient;
        Time mTime;
        private String[] mDayNames, mMonthNames;

        private float mTimeYOffset = -1, mDateYOffset, mSeparatorYOffset, mWeatherYOffset;
        private float defaultOffset;

        private Paint mBackgroundPaint, mTimePaint, mSecondsPaint, mDatePaint, mMaxPaint, mMinPaint;
        private GoogleApiClient googleClient;

        private String lastMaxTemp;
        private String lastMinTemp;
        private Bitmap mWeatherIconBitmap;
        private Bitmap mWeatherIconGrayBitmap;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            googleClient = new GoogleApiClient.Builder(WeatherWatchFace.this).addApi(Wearable.API).addConnectionCallbacks(this).addOnConnectionFailedListener(this).build();
            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            mTime = new Time();

            //Get day and month names
            DateFormatSymbols symbols = new DateFormatSymbols();
            mDayNames = symbols.getShortWeekdays();
            mMonthNames = symbols.getShortMonths();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            isRound = insets.isRound();
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
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mTime.setToNow();
            initValues();
            paintBackground(canvas, bounds);
            paintDateTime(canvas, bounds);
            paintWeather(canvas, bounds);

            paintExtras(canvas, bounds);

        }

        /**
         * Create the tools and values to be used to
         * locate and draw the information in the watch face
         */
        @TargetApi(Build.VERSION_CODES.M)
        private void initValues() {
            if (mTimeYOffset >= 0) {
                return;
            }

            Resources resources = WeatherWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.sunshine_background, getTheme()));

            defaultOffset = resources.getDimension(R.dimen.default_margin_top);
            int whiteColor = resources.getColor(R.color.digital_text, getTheme());
            int grayColor = resources.getColor(R.color.graydigital_text, getTheme());

            float textSizeTime = resources.getDimension(R.dimen.text_size_time);
            mTimePaint = createTextPaint(whiteColor, textSizeTime);

            int marginTop = isRound? R.dimen.time_margin_top_round: R.dimen.time_margin_top;
            mTimeYOffset = resources.getDimension(marginTop) + textSizeTime;

            float textSizeSeconds = resources.getDimension(R.dimen.text_size_seconds);
            mSecondsPaint = createTextPaint(grayColor, textSizeSeconds);


            float textSizeDate = resources.getDimension(R.dimen.text_size_date);
            mDatePaint = createTextPaint(whiteColor, textSizeDate);
            mDateYOffset = defaultOffset + mTimeYOffset + textSizeDate;

            mSeparatorYOffset = defaultOffset + mDateYOffset;

            float textSizeWeather = resources.getDimension(R.dimen.text_size_weather);
            mMaxPaint = createTextPaint(whiteColor, textSizeWeather);
            mMinPaint = createTextPaint(grayColor, textSizeWeather);
            mWeatherYOffset = defaultOffset + mSeparatorYOffset + textSizeWeather;
        }

        private Paint createTextPaint(int textColor, float textSize) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            paint.setTextSize(textSize);
            return paint;
        }

        /**
         * Draw background from the watch face.
         * This can be updated to draw an image or animation if needed later on
         */
        private void paintBackground(Canvas canvas, Rect bounds) {
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }
        }

        /**
         * Draw additional content that is not part of the information displayed
         */
        private void paintExtras(Canvas canvas, Rect bounds) {
            canvas.drawRect(bounds.centerX() - defaultOffset,
                    mSeparatorYOffset, bounds.centerX() + defaultOffset,
                    mSeparatorYOffset + 1,
                    mSecondsPaint);
        }

        /**
         * Draw the information from the date and time into the watch face
         */
        private void paintDateTime(Canvas canvas, Rect bounds) {
            float centerX = bounds.centerX();

            String timeGeneral = String.format("%02d:%02d", mTime.hour, mTime.minute);

            float timeXOffset = mTimePaint.measureText(timeGeneral) / 2;
            canvas.drawText(timeGeneral, centerX - timeXOffset, mTimeYOffset, mTimePaint);

            if (!isInAmbientMode()) {
                String timeSeconds = String.format(":%02d", mTime.second);
                canvas.drawText(timeSeconds, centerX + timeXOffset, mTimeYOffset, mSecondsPaint);
            }

            String date = String.format("%s, %s %02d %04d", mDayNames[mTime.weekDay+1].toUpperCase(), mMonthNames[mTime.month].toUpperCase(), mTime.monthDay, mTime.year);
            float dateXOffset = mDatePaint.measureText(date) / 2;
            canvas.drawText(date, centerX - dateXOffset, mDateYOffset, mDatePaint);
        }

        /**
         * Draw the information from the weather into the watch face
         */
        private void paintWeather(Canvas canvas, Rect bounds) {
            float centerX = bounds.centerX();

            float maxXOffset = 0;
            if (lastMaxTemp!=null){
                maxXOffset = mTimePaint.measureText(lastMaxTemp) / 2;
                canvas.drawText(lastMaxTemp, centerX - maxXOffset, mWeatherYOffset, mMaxPaint);
            }

            if (lastMinTemp!=null) {
                canvas.drawText(lastMinTemp, centerX + maxXOffset + defaultOffset, mWeatherYOffset, mMinPaint);
            }

            if (!isInAmbientMode()) {
                if (mWeatherIconBitmap!=null){
                    float iconXOffset = centerX - (defaultOffset + maxXOffset + mWeatherIconBitmap.getWidth());
                    float iconYOffset = mWeatherYOffset - (defaultOffset+ mWeatherIconBitmap.getHeight())/2;
                    canvas.drawBitmap(mWeatherIconBitmap, iconXOffset, iconYOffset, null);
                }
            } else {
                if (mWeatherIconGrayBitmap!=null) {
                    float iconXOffset = centerX - (defaultOffset + maxXOffset + mWeatherIconGrayBitmap.getWidth());
                    float iconYOffset = mWeatherYOffset - (defaultOffset+ mWeatherIconGrayBitmap.getHeight())/2;
                    canvas.drawBitmap(mWeatherIconGrayBitmap, iconXOffset, iconYOffset, null);
                }
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
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                connectGoogleApiClient();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                releaseGoogleApiClient();
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void connectGoogleApiClient() {
            if (googleClient != null && !googleClient.isConnected()) {
                googleClient.connect();
            }
        }

        private void releaseGoogleApiClient() {
            if (googleClient != null && googleClient.isConnected()) {
                Wearable.DataApi.removeListener(googleClient, this);
                googleClient.disconnect();
            }
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WeatherWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(googleClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event:dataEventBuffer){
                if (event.getType()==DataEvent.TYPE_CHANGED){
                    DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                    String path = event.getDataItem().getUri().getPath();
                    if (path.equals("/sunshine_weather")){
                        lastMaxTemp = dataMap.getString("maxTemp");
                        lastMinTemp = dataMap.getString("minTemp");
                        int weatherId = dataMap.getInt("weatherId");

                        int resId = Utility.getArtResourceForWeatherCondition(weatherId);
                        if (resId>=0){
                            mWeatherIconBitmap = BitmapFactory.decodeResource(getResources(), resId);
                            int size = Double.valueOf(WeatherWatchFace.this.getResources().getDimension(R.dimen.weather_icon_size)).intValue();
                            mWeatherIconBitmap = Bitmap.createScaledBitmap(mWeatherIconBitmap, size, size, false);
                            initGrayBackgroundBitmap();
                        }
                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        /**
         * Generate gray bitmap from the current weather icon
         */
        private void initGrayBackgroundBitmap() {
            mWeatherIconGrayBitmap = Bitmap.createBitmap(
                    mWeatherIconBitmap.getWidth(),
                    mWeatherIconBitmap.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(mWeatherIconGrayBitmap);
            Paint grayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            grayPaint.setColorFilter(filter);
            canvas.drawBitmap(mWeatherIconBitmap, 0, 0, grayPaint);
        }
    }
}
