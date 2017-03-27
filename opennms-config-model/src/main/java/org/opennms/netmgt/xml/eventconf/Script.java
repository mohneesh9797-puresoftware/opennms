/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2011-2014 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2014 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.xml.eventconf;

import java.io.Serializable;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlValue;

import org.opennms.core.xml.ValidateUsing;

/**
 * The script information for this event - describes a
 *  script to be executed whenever the event occurs
 */
@XmlRootElement(name="script")
@XmlAccessorType(XmlAccessType.FIELD)
@ValidateUsing("eventconf.xsd")
public class Script implements Serializable {
	private static final long serialVersionUID = 7297438677703089801L;

	@XmlValue
    private String m_content = "";

	// @NotNull
	@XmlAttribute(name="language", required=true)
    private String m_language;

    public String getContent() {
        return m_content;
    }

    public String getLanguage() {
        return m_language;
    }

    public void setContent(final String content) {
        m_content = content.intern();
    }

    public void setLanguage(final String language) {
        m_language = language.intern();
    }

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((m_content == null) ? 0 : m_content.hashCode());
		result = prime * result + ((m_language == null) ? 0 : m_language.hashCode());
		return result;
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj) return true;
		if (obj == null) return false;
		if (!(obj instanceof Script)) return false;
		final Script other = (Script) obj;
		if (m_content == null) {
			if (other.m_content != null) return false;
		} else if (!m_content.equals(other.m_content)) {
			return false;
		}
		if (m_language == null) {
			if (other.m_language != null) return false;
		} else if (!m_language.equals(other.m_language)) {
			return false;
		}
		return true;
	}

}
