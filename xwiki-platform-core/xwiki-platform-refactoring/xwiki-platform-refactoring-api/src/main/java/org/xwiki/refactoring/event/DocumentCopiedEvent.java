/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.refactoring.event;

import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.event.EndFoldEvent;
import org.xwiki.refactoring.internal.event.AbstractEntityCopyOrRenameEvent;

/**
 * Event fired after a document has been copied.
 * 
 * @version $Id$
 * @since 11.1RC1
 */
public class DocumentCopiedEvent extends AbstractEntityCopyOrRenameEvent<DocumentReference> implements EndFoldEvent
{
    /**
     * Default constructor, used by listeners.
     */
    public DocumentCopiedEvent()
    {
    }

    /**
     * Creates a new instance with the given data.
     * 
     * @param sourceReference the reference of the source entity
     * @param targetReference the reference of the target entity
     */
    public DocumentCopiedEvent(DocumentReference sourceReference, DocumentReference targetReference)
    {
        super(sourceReference, targetReference);
    }
}
