/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.esusers;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.inject.internal.Nullable;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.shield.ShieldException;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.shield.authc.RealmConfig;
import org.elasticsearch.shield.authc.support.Hasher;
import org.elasticsearch.shield.authc.support.RefreshListener;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.shield.support.Validation;
import org.elasticsearch.watcher.FileChangesListener;
import org.elasticsearch.watcher.FileWatcher;
import org.elasticsearch.watcher.ResourceWatcherService;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.elasticsearch.shield.support.ShieldFiles.openAtomicMoveWriter;

/**
 *
 */
public class FileUserPasswdStore {

    private final ESLogger logger;

    private final Path file;
    final Hasher hasher = Hasher.HTPASSWD;

    private volatile ImmutableMap<String, char[]> esUsers;

    private CopyOnWriteArrayList<RefreshListener> listeners;

    public FileUserPasswdStore(RealmConfig config, ResourceWatcherService watcherService) {
        this(config, watcherService, null);
    }

    FileUserPasswdStore(RealmConfig config, ResourceWatcherService watcherService, RefreshListener listener) {
        logger = config.logger(FileUserPasswdStore.class);
        file = resolveFile(config.settings(), config.env());
        esUsers = parseFile(file, logger);
        if (esUsers.isEmpty() && logger.isDebugEnabled()) {
            logger.debug("realm [esusers] has no users");
        }
        FileWatcher watcher = new FileWatcher(file.getParent().toFile());
        watcher.addListener(new FileListener());
        watcherService.add(watcher, ResourceWatcherService.Frequency.HIGH);
        listeners = new CopyOnWriteArrayList<>();
        if (listener != null) {
            listeners.add(listener);
        }
    }

    void addListener(RefreshListener listener) {
        listeners.add(listener);
    }

    public boolean verifyPassword(String username, SecuredString password) {
        if (esUsers == null) {
            return false;
        }
        char[] hash = esUsers.get(username);
        if (hash == null) {
            return false;
        }
        return hasher.verify(password, hash);
    }

    public static Path resolveFile(Settings settings, Environment env) {
        String location = settings.get("files.users");
        if (location == null) {
            return ShieldPlugin.resolveConfigFile(env, "users");
        }
        return Paths.get(location);
    }

    /**
     * parses the esusers file. Should never return {@code null}, if the file doesn't exist an
     * empty map is returned
     */
    public static ImmutableMap<String, char[]> parseFile(Path path, @Nullable ESLogger logger) {
        if (logger != null) {
            logger.trace("reading users file located at [{}]", path.toAbsolutePath());
        }
        if (!Files.exists(path)) {
            return ImmutableMap.of();
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(path, Charsets.UTF_8);
        } catch (IOException ioe) {
            throw new ShieldException("could not read users file [" + path.toAbsolutePath() + "]", ioe);
        }

        ImmutableMap.Builder<String, char[]> users = ImmutableMap.builder();

        int lineNr = 0;
        for (String line : lines) {
            lineNr++;
            if (line.startsWith("#")) { // comment
                continue;
            }
            int i = line.indexOf(":");
            if (i <= 0 || i == line.length() - 1) {
                if (logger != null) {
                    logger.error("invalid entry in users file [{}], line [{}]. skipping...", path.toAbsolutePath(), lineNr);
                }
                continue;
            }
            String username = line.substring(0, i).trim();
            Validation.Error validationError = Validation.ESUsers.validateUsername(username);
            if (validationError != null) {
                if (logger != null) {
                    logger.error("invalid username [{}] in users file [{}], skipping... ({})", username, path.toAbsolutePath(), validationError);
                }
                continue;
            }
            String hash = line.substring(i + 1).trim();
            users.put(username, hash.toCharArray());
        }

        ImmutableMap<String, char[]> usersMap = users.build();
        if (logger != null && usersMap.isEmpty()){
            logger.warn("no users found in users file [{}]. use bin/shield/esusers to add users and role mappings", path.toAbsolutePath());
        }
        return usersMap;
    }

    public static void writeFile(Map<String, char[]> esUsers, Path path) {
        try (PrintWriter writer = new PrintWriter(openAtomicMoveWriter(path))) {
            for (Map.Entry<String, char[]> entry : esUsers.entrySet()) {
                writer.printf(Locale.ROOT, "%s:%s%s", entry.getKey(), new String(entry.getValue()), System.lineSeparator());
            }
        } catch (IOException ioe) {
            throw new ElasticsearchException("could not write file [" + path.toAbsolutePath() + "], please check file permissions", ioe);
        }
    }

    protected void notifyRefresh() {
        for (RefreshListener listener : listeners) {
            listener.onRefresh();
        }
    }

    private class FileListener extends FileChangesListener {
        @Override
        public void onFileCreated(File file) {
            onFileChanged(file);
        }

        @Override
        public void onFileDeleted(File file) {
            onFileChanged(file);
        }

        @Override
        public void onFileChanged(File file) {
            if (file.equals(FileUserPasswdStore.this.file.toFile())) {
                try {
                    esUsers = parseFile(file.toPath(), logger);
                    logger.info("updated users (users file [{}] changed)", file.getAbsolutePath());
                } catch (Throwable t) {
                    logger.error("failed to parse users file [{}]. current users remain unmodified", t, file.getAbsolutePath());
                    return;
                }
                notifyRefresh();
            }
        }
    }
}
