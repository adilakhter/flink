/*
 *    This program is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 2 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program; if not, write to the Free Software
 *    Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */


/**********************************************************************************************************************************
 * THIS CLASS IS NOT APACHE COMPATIBLE!                                                                                           *
 * DO NOT PUSH IT TO APACHE MASTER OR ANY PUBLIC REPOSITORY                                                                       *
 *                                                                                                                                *
 * src: https://code.google.com/p/moa/source/browse/src/main/java/moa/core/SizeOf.java?r=5439f06d0f6f18a3be0c8cca67938fbd3dd5005d *
 **********************************************************************************************************************************/

package org.apache.flink.streaming.paper.adwinPolicies.helper;

/**
 * Helper class for <a href="http://www.jroller.com/maxim/entry/again_about_determining_size_of" target="_blank">Maxim Zakharenkov's SizeOf agent</a>.
 *
 * @author  fracpete (fracpete at waikato dot ac dot nz)
 * @version $Revision$
 */
public class SizeOf {

    /** whether the agent is present. */
    protected static Boolean m_Present;

    /**
     * Checks whteher the agent is present.
     *
     * @return true if the agent is present, false otherwise
     */
    protected static synchronized boolean isPresent() {
        if (m_Present == null) {
            try {
                SizeOfAgent.fullSizeOf(1);
                m_Present = true;
            } catch (Throwable t) {
                m_Present = false;
            }
        }

        return m_Present;
    }

    /**
     * Returns the size of the object.
     *
     * @param o the object to get the size for
     * @return the size of the object, or if the agent isn't present -1
     */
    public static long sizeOf(Object o) {
        if (isPresent()) {
            return SizeOfAgent.sizeOf(o);
        } else {
            return -1;
        }
    }

    /**
     * Returns the full size of the object.
     *
     * @param o the object to get the size for
     * @return the size of the object, or if the agent isn't present -1
     */
    public static long fullSizeOf(Object o) {
        if (isPresent()) {
            return SizeOfAgent.fullSizeOf(o);
        } else {
            return -1;
        }
    }
}
