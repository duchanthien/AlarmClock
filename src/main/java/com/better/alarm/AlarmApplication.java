/*
 * Copyright (C) 2012 Yuriy Kulikov yuriy.kulikov.87@gmail.com
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
package com.better.alarm;

import android.app.AlarmManager;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.ViewConfiguration;

import com.better.alarm.model.AlarmCore;
import com.better.alarm.model.AlarmCoreFactory;
import com.better.alarm.model.AlarmSetter;
import com.better.alarm.model.AlarmStateNotifier;
import com.better.alarm.model.AlarmValue;
import com.better.alarm.model.Alarms;
import com.better.alarm.model.AlarmsScheduler;
import com.better.alarm.model.ContainerFactory;
import com.better.alarm.model.IAlarmsScheduler;
import com.better.alarm.model.MainLooperHandlerFactory;
import com.better.alarm.model.interfaces.Alarm;
import com.better.alarm.model.interfaces.IAlarmsManager;
import com.better.alarm.persistance.DatabaseQuery;
import com.better.alarm.presenter.DynamicThemeHandler;
import com.better.alarm.presenter.background.ScheduledReceiver;
import com.f2prateek.rx.preferences2.RxSharedPreferences;
import com.github.androidutils.logger.LogcatLogWriter;
import com.github.androidutils.logger.LogcatLogWriterWithLines;
import com.github.androidutils.logger.Logger;
import com.github.androidutils.logger.LoggingExceptionHandler;
import com.github.androidutils.logger.StartupLogWriter;
import com.github.androidutils.statemachine.HandlerFactory;
import com.github.androidutils.wakelock.WakeLockManager;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.inject.Binder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.name.Names;

import org.acra.ACRA;
import org.acra.ErrorReporter;
import org.acra.ExceptionHandlerInitializer;
import org.acra.ReportField;
import org.acra.annotation.ReportsCrashes;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.subjects.BehaviorSubject;

@ReportsCrashes(
        mailTo = "yuriy.kulikov.87@gmail.com",
        applicationLogFileLines = 150,
        customReportContent = {
                ReportField.IS_SILENT,
                ReportField.APP_VERSION_CODE,
                ReportField.PHONE_MODEL,
                ReportField.ANDROID_VERSION,
                ReportField.CUSTOM_DATA,
                ReportField.STACK_TRACE,
                ReportField.SHARED_PREFERENCES,
        })
public class AlarmApplication extends Application {

    private static Injector guice;

    @Override
    public void onCreate() {
        // The following line triggers the initialization of ACRA
        ACRA.init(this);
        DynamicThemeHandler.init(this);
        setTheme(DynamicThemeHandler.getInstance().getIdForName(DynamicThemeHandler.DEFAULT));

        try {
            ViewConfiguration config = ViewConfiguration.get(this);
            Field menuKeyField = ViewConfiguration.class.getDeclaredField("sHasPermanentMenuKey");
            if (menuKeyField != null) {
                menuKeyField.setAccessible(true);
                menuKeyField.setBoolean(config, false);
            }
        } catch (Exception ex) {
            // Ignore
        }

        final Logger logger = Logger.getDefaultLogger();
        logger.addLogWriter(LogcatLogWriter.getInstance());
        logger.addLogWriter(StartupLogWriter.getInstance());

        LoggingExceptionHandler.addLoggingExceptionHandlerToAllThreads(logger);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        RxSharedPreferences rxPreferences = RxSharedPreferences.create(preferences);

        Function<String, Integer> parseInt = new Function<String, Integer>() {
            @Override
            public Integer apply(String s) throws Exception {
                return Integer.parseInt(s);
            }
        };

        final ImmutablePrefs prefs = ImmutablePrefs.builder()
                .preAlarmDuration(rxPreferences.getString("prealarm_duration", "30").asObservable().map(parseInt))
                .snoozeDuration(rxPreferences.getString("snooze_duration", "10").asObservable().map(parseInt))
                .autoSilence(rxPreferences.getString("auto_silence", "10").asObservable().map(parseInt))
                .build();

        final ImmutableStore store = ImmutableStore.builder()
                .alarms(BehaviorSubject.<List<AlarmValue>>createDefault(new ArrayList<AlarmValue>()))
                .next(BehaviorSubject.createDefault(Optional.<Store.Next>absent()))
                .build();

        store.alarms().subscribe(new Consumer<List<AlarmValue>>() {
            @Override
            public void accept(@NonNull List<AlarmValue> alarmValues) throws Exception {
                logger.d("Store: ###########################");
                for (AlarmValue alarmValue : alarmValues) {
                    logger.d("Store: " + alarmValue);
                }
                logger.d("Store: ###########################");
            }
        });

        store.next().subscribe(new Consumer<Optional<Store.Next>>() {
            @Override
            public void accept(@NonNull Optional<Store.Next> next) throws Exception {
                logger.d("Store: #######################    " + next );
            }
        });

        guice = Guice.createInjector(new Module() {
            @Override
            public void configure(Binder binder) {
                binder.requireExplicitBindings();
                binder.requireAtInjectOnConstructors();
                binder.requireExactBindingAnnotations();

                binder.bind(Context.class).toInstance(getApplicationContext());
                binder.bind(Logger.class).toInstance(logger);
                binder.bind(Logger.class).annotatedWith(Names.named("debug")).toInstance(new Logger());
                binder.bind(Prefs.class).toInstance(prefs);
                binder.bind(AlarmManager.class).toInstance((AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE));
                binder.bind(SharedPreferences.class).toInstance(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));
                binder.bind(ContentResolver.class).toInstance(getApplicationContext().getContentResolver());

                //eager singletons to fail faster
                binder.bind(WakeLockManager.class).asEagerSingleton();
                binder.bind(IAlarmsManager.class).to(Alarms.class).asEagerSingleton();
                binder.bind(IAlarmsScheduler.class).to(AlarmsScheduler.class).asEagerSingleton();
                binder.bind(AlarmCoreFactory.class).asEagerSingleton();
                binder.bind(HandlerFactory.class).to(MainLooperHandlerFactory.class).asEagerSingleton();
                binder.bind(DatabaseQuery.class).asEagerSingleton();
                binder.bind(AlarmCore.IStateNotifier.class).to(AlarmStateNotifier.class).asEagerSingleton();
                binder.bind(Alarms.class).asEagerSingleton();

                binder.bind(Store.class).toInstance(store);
                binder.bind(ScheduledReceiver.class);
                binder.bind(ContainerFactory.class).to(ContainerFactory.ContainerFactoryImpl.class);
                binder.bind(AlarmSetter.class).to(AlarmSetter.AlarmSetterImpl.class);
            }
        });

        ACRA.getErrorReporter().setExceptionHandlerInitializer(new ExceptionHandlerInitializer() {
            @Override
            public void initializeExceptionHandler(ErrorReporter reporter) {
                reporter.putCustomData("STARTUP_LOG", StartupLogWriter.getInstance().getMessagesAsString());
            }
        });

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        guice.getInstance(ScheduledReceiver.class);

        deleteLogs(getApplicationContext());

        logger.d("onCreate");
        super.onCreate();
    }

    public static Injector guice() {
        return Preconditions.checkNotNull(guice);
    }

    private void deleteLogs(Context context) {
        final File logFile = new File(context.getFilesDir(), "applog.log");
        if (logFile.exists()) {
            logFile.delete();
        }
    }

    public static WakeLockManager wakeLocks() {
        return guice.getInstance(WakeLockManager.class);
    }

    public static IAlarmsManager alarms() {
        return guice.getInstance(IAlarmsManager.class);
    }
}
