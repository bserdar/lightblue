 /*
 Copyright 2013 Red Hat, Inc. and/or its affiliates.

 This file is part of lightblue.

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.redhat.lightblue.mindex;

import java.util.Arrays;

/**
 * ArrayKey wraps an array of key values, each of which can be simple or array keys
 */
class ArrayKey implements Key {
    final Key[] values;

    int hcode;
    boolean hcodeInitialized;
    
    ArrayKey(Key[] values) {
        this.values=values;
    }

    /**
     * The simple optimization of caching hashcode appears to improve the speed by x2
     */
    @Override
    public int hashCode() {
        if(!hcodeInitialized) {
            int h=0;
            for(Key k:values)
                h*=k.hashCode()+1;
            hcode=h;
            hcodeInitialized=true;
        }
        return hcode;
    }
    
    @Override
    public boolean equals(Object o) {
        if(o.hashCode()==hashCode()) {
            if(o==this)
                return true;
            for(int i=0;i<values.length;i++) {
                if(!values[i].equals( ((ArrayKey)o).values[i]))
                    return false;
            }
            return true;
        }
        return false;
    }
    
    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
    
