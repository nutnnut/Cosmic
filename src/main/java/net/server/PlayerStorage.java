/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.server;

import client.Character;
import client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PlayerStorage {
    private static final Logger log = LoggerFactory.getLogger(PlayerStorage.class);
    private final Map<Integer, Character> storage = new LinkedHashMap<>();
    private final Map<String, Character> nameStorage = new LinkedHashMap<>();
    private final Lock rlock;
    private final Lock wlock;

    public PlayerStorage() {
        ReadWriteLock readWriteLock = new ReentrantReadWriteLock(true);
        this.rlock = readWriteLock.readLock();
        this.wlock = readWriteLock.writeLock();
    }

    public void addPlayer(Character chr) {
        wlock.lock();
        try {
            storage.put(chr.getId(), chr);
            nameStorage.put(chr.getName().toLowerCase(), chr);
        } finally {
            wlock.unlock();
        }
    }

    public Character removePlayer(int chr) {
        wlock.lock();
        try {
            Character mc = storage.remove(chr);
            if (mc != null) {
                nameStorage.remove(mc.getName().toLowerCase());
            }

            return mc;
        } finally {
            wlock.unlock();
        }
    }

    public Character getCharacterByName(String name) {
        rlock.lock();
        try {
            return nameStorage.get(name.toLowerCase());
        } finally {
            rlock.unlock();
        }
    }

    public Character getCharacterById(int id) {
        rlock.lock();
        try {
            return storage.get(id);
        } finally {
            rlock.unlock();
        }
    }

    public Collection<Character> getAllCharacters() {
        rlock.lock();
        try {
            return new ArrayList<>(storage.values());
        } finally {
            rlock.unlock();
        }
    }

    public final void disconnectAll() {
        List<Character> chrList;
        rlock.lock();
        try {
            chrList = new ArrayList<>(storage.values());
        } finally {
            rlock.unlock();
        }

        // Each forceDisconnect() saves the char to the DB, so done one at a time this dominates
        // shutdown once hundreds of bots are online. Fan it across a bounded pool: the save is
        // per-char (saveCharToDB is synchronized on the Character and writes only that char's own
        // rows), concurrency is capped by SAVE_GATE in Character (see there for the deadlock
        // history and root-cause fix), and disconnectAll is shutdown-only (no live gameplay to
        // race). Per-task try/catch + a 2-min awaitTermination guard so one stuck char can't lose
        // the rest. Single-char path stays inline.
        if (chrList.size() <= 1) {
            for (Character mc : chrList) {
                Client client = mc.getClient();
                if (client != null) {
                    client.forceDisconnect();
                }
            }
        } else {
            int workers = Math.min(8, chrList.size());
            ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
                Thread t = new Thread(r, "shutdown-save");
                t.setDaemon(true);
                return t;
            });
            for (Character mc : chrList) {
                pool.execute(() -> {
                    try {
                        Client client = mc.getClient();
                        if (client != null) {
                            client.forceDisconnect();
                        }
                    } catch (RuntimeException e) {
                        log.error("Failed to disconnect/save chr {} during shutdown", mc.getId(), e);
                    }
                });
            }
            pool.shutdown();
            try {
                if (!pool.awaitTermination(2, TimeUnit.MINUTES)) {
                    log.warn("Shutdown save did not finish within 2 min; {} chars may not have saved",
                            chrList.size());
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        wlock.lock();
        try {
            storage.clear();
        } finally {
            wlock.unlock();
        }
    }

    public int getSize() {
        rlock.lock();
        try {
            return storage.size();
        } finally {
            rlock.unlock();
        }
    }
}
