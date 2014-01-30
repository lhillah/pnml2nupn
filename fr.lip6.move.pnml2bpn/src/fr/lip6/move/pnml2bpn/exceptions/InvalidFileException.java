/**
 *  Copyright 2014 Universite Paris Ouest Nanterre & Sorbonne Universites, Univ. Paris 06 - CNRS UMR 7606 (LIP6/MoVe)
 *  All rights reserved.   This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Project leader / Initial Contributor:
 *    Lom Messan Hillah - <lom-messan.hillah@lip6.fr>
 *
 *  Contributors:
 *    ${ocontributors} - <$oemails}>
 *
 *  Mailing list:
 *    lom-messan.hillah@lip6.fr
 */
package fr.lip6.move.pnml2bpn.exceptions;

/**
 * Documents every IO exception: file not found, null pointer exception, etc.
 * 
 * @author lhillah
 * 
 */
public class InvalidFileException extends Exception {

	/**
	 * Serialization stuff.
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * Default Constructor.
	 */
	public InvalidFileException() {
		super();
	}

	/**
	 * With message.
	 * 
	 * @param message
	 *            the messages
	 */
	public InvalidFileException(String message) {
		super(message);
	}

	/**
	 * With cause.
	 * 
	 * @param cause
	 *            the cause
	 */
	public InvalidFileException(Throwable cause) {
		super(cause);
	}

	/**
	 * With message and cause.
	 * 
	 * @param message
	 *            the message
	 * @param cause
	 *            the cause
	 */
	public InvalidFileException(String message, Throwable cause) {
		super(message, cause);
	}

}
