package flhan.de.financemanager

import android.app.Activity
import android.app.Application
import android.app.Service
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasActivityInjector
import dagger.android.HasServiceInjector
import flhan.de.financemanager.di.DaggerAppComponent
import javax.inject.Inject


/**
 * Created by Florian on 10.09.2017.
 */
class App : Application(), HasActivityInjector, HasServiceInjector {

    @Inject
    lateinit var serviceDispatchingAndroidInjector: DispatchingAndroidInjector<Service>

    @Inject
    lateinit var activityDispatchingAndroidInjector: DispatchingAndroidInjector<Activity>

    override fun onCreate() {
        super.onCreate()
        DaggerAppComponent.builder()
                .application(this)
                .build()
                .inject(this)

        /*if (LeakCanary.isInAnalyzerProcess(this)) {
            return
        }
        LeakCanary.install(this)*/
    }

    override fun activityInjector(): AndroidInjector<Activity> {
        return activityDispatchingAndroidInjector
    }

    override fun serviceInjector(): AndroidInjector<Service> {
        return serviceDispatchingAndroidInjector
    }
}