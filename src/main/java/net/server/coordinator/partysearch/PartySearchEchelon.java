/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

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
package net.server.coordinator.partysearch;

import client.Character;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Ronan
 */
public class PartySearchEchelon {
    // Every method mutates `echelon` (put/remove/clear), so all access is exclusive — a plain lock.
    // (Was a ReadWriteLock whose read lock guarded the mutating attach/detach, which corrupted the
    // non-thread-safe HashMap under concurrency: size() went negative -> "Illegal Capacity: -1".)
    private final Lock psWLock = new ReentrantLock(true);

    private final Map<Integer, WeakReference<Character>> echelon = new HashMap<>(20);

    public PartySearchEchelon() {
    }

    public List<Character> exportEchelon() {
        psWLock.lock();     // reversing read/write actually could provide a lax yet sure performance/precision trade-off here
        try {
            List<Character> players = new ArrayList<>(echelon.size());

            for (WeakReference<Character> chrRef : echelon.values()) {
                Character chr = chrRef.get();
                if (chr != null) {
                    players.add(chr);
                }
            }

            echelon.clear();
            return players;
        } finally {
            psWLock.unlock();
        }
    }

    public void attachPlayer(Character chr) {
        psWLock.lock();     // WRITE lock: this mutates the (non-thread-safe) HashMap. Using the shared
        try {               // read lock let concurrent attach/detach corrupt size() -> negative ->
            echelon.put(chr.getId(), new WeakReference<>(chr));   // "Illegal Capacity: -1" in export.
        } finally {
            psWLock.unlock();
        }
    }

    public boolean detachPlayer(Character chr) {
        psWLock.lock();     // WRITE lock: mutating the HashMap must be exclusive (see attachPlayer).
        try {
            return echelon.remove(chr.getId()) != null;
        } finally {
            psWLock.unlock();
        }
    }

}
