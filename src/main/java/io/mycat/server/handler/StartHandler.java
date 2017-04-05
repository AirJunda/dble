/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat.server.handler;

import io.mycat.log.transaction.TxnLogHelper;
import io.mycat.net.mysql.OkPacket;
import io.mycat.config.ErrorCode;
import io.mycat.server.ServerConnection;
import io.mycat.server.parser.ServerParse;
import io.mycat.server.parser.ServerParseStart;

/**
 * @author mycat
 */
public final class StartHandler {
    public static void handle(String stmt, ServerConnection c, int offset) {
        switch (ServerParseStart.parse(stmt, offset)) {
        case ServerParseStart.TRANSACTION:
	    if (c.isTxstart() || !c.isAutocommit()) {
		c.beginInTx(stmt);
	    } else {
		c.setTxstart(true);
		TxnLogHelper.putTxnLog(c, stmt);
		c.write(c.writeToBuffer(OkPacket.OK, c.allocate()));
	    }
            break;
	case ServerParseStart.READCHARCS:
	    c.writeErrMessage(ErrorCode.ER_YES, "Unsupported statement");
	    break;
        default:
            c.execute(stmt, ServerParse.START);
        }
    }

}
