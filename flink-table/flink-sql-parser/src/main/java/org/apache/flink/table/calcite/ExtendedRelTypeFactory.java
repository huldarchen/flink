/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.calcite;

import org.apache.flink.annotation.Internal;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;

import java.util.List;

/**
 * A factory for creating {@link RelDataType} instances including Flink-specific extensions.
 *
 * <p>This interface exists because the parser module has no access to the planner's type factory.
 */
@Internal
public interface ExtendedRelTypeFactory extends RelDataTypeFactory {

    /** Creates a RAW type such as {@code RAW('org.my.Class', 'sW3Djsds...')}. */
    RelDataType createRawType(String className, String serializerString);

    /**
     * Creates a STRUCTURED type such as {@code STRUCTURED('org.my.Class', name STRING, age INT)}.
     */
    RelDataType createStructuredType(
            String className, List<RelDataType> typeList, List<String> fieldNameList);
}
