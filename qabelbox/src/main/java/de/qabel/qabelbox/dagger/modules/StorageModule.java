package de.qabel.qabelbox.dagger.modules;

import android.content.Context;

import java.io.File;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import de.qabel.desktop.repository.IdentityRepository;
import de.qabel.qabelbox.account.AccountManager;
import de.qabel.qabelbox.communication.BoxAccountRegisterServer;
import de.qabel.qabelbox.config.AppPreference;
import de.qabel.qabelbox.providers.DocumentIdParser;
import de.qabel.qabelbox.storage.AndroidBoxManager;
import de.qabel.qabelbox.storage.BoxManager;
import de.qabel.qabelbox.storage.notifications.AndroidStorageNotificationManager;
import de.qabel.qabelbox.storage.notifications.AndroidStorageNotificationPresenter;
import de.qabel.qabelbox.storage.notifications.StorageNotificationManager;
import de.qabel.qabelbox.storage.server.AndroidBlockServer;
import de.qabel.qabelbox.storage.server.BlockServer;
import de.qabel.qabelbox.storage.transfer.BlockServerTransferManager;
import de.qabel.qabelbox.storage.transfer.TransferManager;

@Module
public class StorageModule {

    @Singleton
    @Provides
    StorageNotificationManager providesStorageNotificationManager(Context context) {
        return new AndroidStorageNotificationManager(new AndroidStorageNotificationPresenter(context));
    }

    @Singleton
    @Provides
    DocumentIdParser providesDocumentIdParser() {
        return new DocumentIdParser();
    }

    @Provides
    File providesCacheDir(Context context) {
        return context.getCacheDir();
    }

    @Provides
    public TransferManager providesTransferManager(Context context, BlockServer blockServer, File tmpFile) {
        return createTransferManager(context, blockServer, tmpFile);
    }

    protected TransferManager createTransferManager(Context context, BlockServer blockServer, File tmpFile) {
        return new BlockServerTransferManager(context, blockServer, tmpFile);
    }

    @Singleton
    @Provides
    BoxManager providesBoxManager(Context context,
                                  StorageNotificationManager notificationManager,
                                  DocumentIdParser documentIdParser,
                                  AppPreference preferences,
                                  TransferManager transferManager,
                                  IdentityRepository identityRepository) {
        return new AndroidBoxManager(context, notificationManager,
                documentIdParser, preferences, transferManager, identityRepository);
    }

    @Singleton
    @Provides
    BlockServer providesBlockServer(AppPreference preference, Context context) {
        return createBlockServer(preference, context);
    }

    protected BlockServer createBlockServer(AppPreference preference, Context context){
        return new AndroidBlockServer(preference, context);
    }

}
