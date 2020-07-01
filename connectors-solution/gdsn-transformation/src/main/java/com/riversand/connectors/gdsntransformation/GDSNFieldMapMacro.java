package com.riversand.connectors.gdsntransformation;

import com.google.common.base.Strings;

import com.riversand.rsconnect.common.transform.FieldMapMacro;

public class GDSNFieldMapMacro extends FieldMapMacro {

   public static boolean isPath(String field) {
      return !Strings.isNullOrEmpty(field) && field.startsWith("@path");
   }

   public static String getPath(String field) {
      if (!isPath(field)) {
         return field;
      }

      int endIndex = field.lastIndexOf(')');
      if (endIndex == -1) {
         return field;
      }

      return field.substring("@path".length() + 1, endIndex);
   }
}
