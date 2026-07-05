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
package server.maps;

import java.awt.*;

/**
 * @author Matze
 */
public class Foothold implements Comparable<Foothold> {
    private final Point p1;
    private final Point p2;
    private final int id;
    private int next, prev;
    private boolean forbidFallDown;
    // WZ storage path is foothold/<layer>/<group>/<id>; the client keeps both on CStaticFoothold
    // (m_lPage/m_lZMass, v95 PDB) and scopes airborne wall collision by zMass group. -1 = unknown
    // (synthetic/hand-built test footholds), which collision code treats as "always collidable".
    private int layer = -1;
    private int zMass = -1;

    public Foothold(Point p1, Point p2, int id) {
        this.p1 = p1;
        this.p2 = p2;
        this.id = id;
    }

    public boolean isWall() {
        return p1.x == p2.x;
    }

    public int getX1() {
        return p1.x;
    }

    public int getX2() {
        return p2.x;
    }

    public int getY1() {
        return p1.y;
    }

    public int getY2() {
        return p2.y;
    }

    public double slope() {
        if (isWall()) {
            return 0.0;
        }
        return (double) (p2.y - p1.y) / (double) (p2.x - p1.x);
    }

    // XXX may need more precision
    public int calculateFooting(int x) {
        if (p1.y == p2.y) {
            return p2.y; // y at both ends is the same
        }
        int slope = (p1.y - p2.y) / (p1.x - p2.x);
        int intercept = p1.y - (slope * p1.x);
        return (slope * x) + intercept;
    }

    @Override
    public int compareTo(Foothold o) {
        Foothold other = o;
        if (p2.y < other.getY1()) {
            return -1;
        } else if (p1.y > other.getY2()) {
            return 1;
        } else {
            return 0;
        }
    }

    public int getId() {
        return id;
    }

    public int getNext() {
        return next;
    }

    public void setNext(int next) {
        this.next = next;
    }

    public int getPrev() {
        return prev;
    }

    public void setPrev(int prev) {
        this.prev = prev;
    }

    public boolean isForbidFallDown() {
        return forbidFallDown;
    }

    public void setForbidFallDown(boolean forbidFallDown) {
        this.forbidFallDown = forbidFallDown;
    }

    public int getLayer() {
        return layer;
    }

    public void setLayer(int layer) {
        this.layer = layer;
    }

    public int getZMass() {
        return zMass;
    }

    public void setZMass(int zMass) {
        this.zMass = zMass;
    }
}
