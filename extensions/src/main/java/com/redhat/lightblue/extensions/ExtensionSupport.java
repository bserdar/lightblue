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
package com.redhat.lightblue.extensions;

/**
 * Controllers supporting extensions must implement this interface
 *
 * Here's how it all comes together:
 *
 * - XXXCrudController: implement ExtensionSupport interface. Implement the
 * getExtensionInstance that returns an instance of the requested extension. -
 * The extension implementation extends Extension interface.
 */
public interface ExtensionSupport {
    /**
     * Returns an instance of the given extension
     *
     * @param extensionClass The class for the requested extension
     *
     * @return An instance of the extension, or null if the controller doesn't
     * support this extension
     */
    <E extends Extension> E getExtensionInstance(Class<? extends Extension> extensionClass);
}
