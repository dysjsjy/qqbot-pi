package dev.qqbot.message;
import java.util.*;
public final class Texts {
 private Texts(){}
 public static List<String> chunks(String text,int maxCodepoints){
  if(maxCodepoints<1)throw new IllegalArgumentException("Invalid limit");
  List<String> result=new ArrayList<>();int start=0,remaining=text.codePointCount(0,text.length());
  while(remaining>0){int count=Math.min(maxCodepoints,remaining);int end=text.offsetByCodePoints(start,count);result.add(text.substring(start,end));start=end;remaining-=count;}
  return result;
 }
 public static List<String> delivery(String text){int length=text.codePointCount(0,text.length());if(length<=1500)return chunks(text,500);var result=new ArrayList<>(chunks(text.substring(0,text.offsetByCodePoints(0,1500)),500));String suffix="\n（展示已截断，完整报告请在管理端查看）";result.set(2,chunks(result.get(2),500-suffix.codePointCount(0,suffix.length())).get(0)+suffix);return result;}
 public static String command(String text){String stripped=text.strip();return stripped.startsWith("/")?stripped.split("\\s+",2)[0]:"";}
}
