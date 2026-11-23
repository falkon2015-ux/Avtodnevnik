package ru.avtodnevnik;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Small dependency-free Excel OOXML exporter. Eight worksheets:
 * weekly summary plus seven separate day reports.
 * All text is written as inlineString cells, preventing formula injection.
 */
final class WeekXlsxWriter {
    private WeekXlsxWriter(){}

    static void write(OutputStream output,Store store,String anyDay) throws IOException{
        String monday=WeekJournal.monday(anyDay);
        JSONArray weekDates=WeekJournal.dates(monday);
        JSONObject week=WeekJournal.get(store,monday);
        try(ZipOutputStream zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){
            put(zip,"[Content_Types].xml",types());
            put(zip,"_rels/.rels",packageRels());
            put(zip,"xl/workbook.xml",workbook(weekDates));
            put(zip,"xl/_rels/workbook.xml.rels",workbookRels());
            put(zip,"xl/styles.xml",styles());
            put(zip,"xl/worksheets/sheet1.xml",overview(week));
            for(int i=0;i<7;i++){
                String key=weekDates.optString(i);
                put(zip,"xl/worksheets/sheet"+(i+2)+".xml",
                    daily(store,key,new String[]{"Понедельник","Вторник","Среда","Четверг","Пятница","Суббота","Воскресенье"}[i]));
            }
            zip.finish();
        }
    }
    private static void put(ZipOutputStream z,String name,String data)throws IOException{
        z.putNextEntry(new ZipEntry(name));
        z.write(data.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
    private static String types(){
        StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        b.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        b.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
        b.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        b.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
        b.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for(int i=1;i<=8;i++)b.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
            .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        return b.append("</Types>").toString();
    }
    private static String packageRels(){
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"+
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"+
            "</Relationships>";
    }
    private static String workbook(JSONArray dates){
        StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        b.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
            .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        b.append("<sheet name=\"Вся неделя\" sheetId=\"1\" r:id=\"rId1\"/>");
        for(int i=0;i<7;i++){
            String day=new String[]{"Пн","Вт","Ср","Чт","Пт","Сб","Вс"}[i];
            String key=dates.optString(i);
            String name=day+" "+key.substring(8,10)+"."+key.substring(5,7);
            b.append("<sheet name=\"").append(xml(name)).append("\" sheetId=\"").append(i+2)
                .append("\" r:id=\"rId").append(i+2).append("\"/>");
        }
        return b.append("</sheets></workbook>").toString();
    }
    private static String workbookRels(){
        StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        b.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for(int i=1;i<=8;i++)b.append("<Relationship Id=\"rId").append(i)
            .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
            .append(i).append(".xml\"/>");
        return b.append("<Relationship Id=\"rId9\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
            .append("</Relationships>").toString();
    }
    private static String styles(){
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"+
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"+
            "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font></fonts>"+
            "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill>"+
            "<fill><patternFill patternType=\"gray125\"/></fill>"+
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF16485A\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"+
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"+
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"+
            "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"+
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs>"+
            "</styleSheet>";
    }
    private static String startSheet(int[] widths){
        StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        b.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        b.append("<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews><sheetFormatPr defaultRowHeight=\"17\"/>");
        b.append("<cols>");
        for(int i=0;i<widths.length;i++)b.append("<col min=\"").append(i+1).append("\" max=\"").append(i+1)
            .append("\" width=\"").append(widths[i]).append("\" customWidth=\"1\"/>");
        b.append("</cols><sheetData>");
        return b.toString();
    }
    private static String xml(String s){
        if(s==null)return "";
        StringBuilder o=new StringBuilder(s.length()+8);
        for(int i=0;i<s.length();i++){
            char ch=s.charAt(i);
            switch(ch){
                case '&':o.append("&amp;");break;
                case '<':o.append("&lt;");break;
                case '>':o.append("&gt;");break;
                case '"':o.append("&quot;");break;
                case '\'':o.append("&apos;");break;
                default:
                    // XML 1.0 disallows most control chars, regardless of input source.
                    if(ch>=0x20||ch=='\n'||ch=='\r'||ch=='\t')o.append(ch);
            }
        }
        return o.toString();
    }
    private static String column(int index){
        StringBuilder name=new StringBuilder();
        int x=index+1;
        while(x>0){x--;name.insert(0,(char)('A'+(x%26)));x/=26;}
        return name.toString();
    }
    private static void rowOpen(StringBuilder b,int row){b.append("<row r=\"").append(row).append("\">");}
    private static void rowClose(StringBuilder b){b.append("</row>");}
    private static void str(StringBuilder b,int row,int col,String val,boolean header){
        b.append("<c r=\"").append(column(col)).append(row).append("\" t=\"inlineStr\"");
        if(header)b.append(" s=\"1\"");
        b.append("><is><t xml:space=\"preserve\">").append(xml(val)).append("</t></is></c>");
    }
    private static void num(StringBuilder b,int row,int col,double value){
        if(!Double.isFinite(value))value=0;
        b.append("<c r=\"").append(column(col)).append(row).append("\"><v>")
            .append(String.format(Locale.US,"%.2f",value)).append("</v></c>");
    }
    private static String overview(JSONObject week){
        StringBuilder b=new StringBuilder(startSheet(new int[]{14,17,15,14,19,19,19,20,20}));
        rowOpen(b,1);str(b,1,0,"АВТОДНЕВНИК — ЖУРНАЛ ЗА НЕДЕЛЮ",true);rowClose(b);
        rowOpen(b,2);str(b,2,0,"С "+week.optString("from")+" по "+week.optString("to"),false);rowClose(b);
        String[] names={"День","Дата","Рабочие периоды","Маршруты","Пробег, км","В движении, мин","На стоянках, мин","Ожидание, мин","Адреса проверить"};
        rowOpen(b,4);
        for(int i=0;i<names.length;i++)str(b,4,i,names[i],true);
        rowClose(b);
        JSONArray days=week.optJSONArray("days");
        if(days!=null)for(int i=0;i<days.length();i++){
            JSONObject d=days.optJSONObject(i);if(d==null)continue;
            int row=i+5;rowOpen(b,row);
            str(b,row,0,d.optString("weekday"),false);
            str(b,row,1,d.optString("date"),false);
            num(b,row,2,d.optInt("sessions"));
            num(b,row,3,d.optInt("legs"));
            num(b,row,4,d.optDouble("km"));
            num(b,row,5,d.optLong("moving"));
            num(b,row,6,d.optLong("stopped"));
            num(b,row,7,d.optLong("breaks"));
            num(b,row,8,d.optInt("missing"));
            rowClose(b);
        }
        JSONObject t=week.optJSONObject("total");
        if(t!=null){
            rowOpen(b,13);str(b,13,0,"ИТОГО ЗА НЕДЕЛЮ",true);
            num(b,13,2,t.optLong("sessions"));num(b,13,3,t.optLong("legs"));
            num(b,13,4,t.optDouble("km"));num(b,13,5,t.optLong("moving"));
            num(b,13,6,t.optLong("stopped"));num(b,13,7,t.optLong("breaks"));rowClose(b);
        }
        rowOpen(b,16);str(b,16,0,"Адрес ближайшего объекта в радиусе 40 м может не совпадать с фактическим местом назначения.",false);rowClose(b);
        rowOpen(b,17);str(b,17,0,"© OpenStreetMap contributors. Адреса требуют проверки. GPS-журнал не является официальным путевым листом.",false);rowClose(b);
        return b.append("</sheetData></worksheet>").toString();
    }
    private static String status(JSONObject p){
        if(p==null)return "Нет сведений";
        int s=p.optInt("status");
        if(s==4)return "Вручную";
        if(s==2)return p.optBoolean("complete")?"Адрес найден":"Город не указан";
        if(s==1)return "Идёт поиск";
        return "Адрес не найден";
    }
    private static String field(JSONObject p,String key){
        return p==null?"":p.optString(key,"");
    }
    private static String daily(Store store,String date,String weekday){
        JSONObject report=WaybillEngine.report(store,date);
        StringBuilder b=new StringBuilder(startSheet(new int[]{
            7,12,21,27,14,12,21,27,14,13,16,20,14,17,37,19,14
        }));
        rowOpen(b,1);str(b,1,0,weekday+" "+date+" — путевой журнал Автодневник",true);rowClose(b);
        rowOpen(b,2);str(b,2,0,"Адреса из OpenStreetMap/системы Android; выбор не далее 40 м от точки GPS.",false);rowClose(b);
        String[] header={"№","Выезд","Город: откуда","Улица: откуда","Дом: откуда",
            "Прибытие","Город: куда","Улица: куда","Дом: куда","Пробег, км",
            "Стоянка, мин","Событие","Источник","До адреса, м","Примечание","Подтверждение","Рабочий период"};
        rowOpen(b,4);for(int i=0;i<header.length;i++)str(b,4,i,header[i],true);rowClose(b);
        JSONArray rows=report.optJSONArray("rows");
        int count=rows==null?0:rows.length();
        for(int i=0;i<count;i++){
            JSONObject line=rows.optJSONObject(i);
            if(line==null)continue;
            int r=i+5;JSONObject from=line.optJSONObject("from"),to=line.optJSONObject("to");
            rowOpen(b,r);
            num(b,r,0,line.optInt("number"));
            str(b,r,1,line.optString("departure"),false);
            str(b,r,2,field(from,"city"),false);
            str(b,r,3,field(from,"street"),false);
            str(b,r,4,field(from,"house"),false);
            str(b,r,5,line.optString("arrival"),false);
            str(b,r,6,field(to,"city"),false);
            str(b,r,7,field(to,"street"),false);
            str(b,r,8,field(to,"house"),false);
            num(b,r,9,line.optDouble("km"));
            num(b,r,10,line.optLong("stopMinutes"));
            str(b,r,11,line.optString("ongoing").equals("true")?"Стоянка":"Прибытие",false);
            str(b,r,12,field(to,"source"),false);
            if(to!=null && to.optDouble("distanceMetres",-1)>=0)num(b,r,13,to.optDouble("distanceMetres"));
            str(b,r,14,line.optString("note"),false);
            str(b,r,15,status(to),false);
            num(b,r,16,line.optInt("period"));
            rowClose(b);
        }
        JSONObject total=report.optJSONObject("total");
        int sum=count+7;
        rowOpen(b,sum);str(b,sum,0,"ИТОГО",true);
        if(total!=null){num(b,sum,9,total.optDouble("km"));num(b,sum,10,total.optLong("stoppedMinutes"));}
        rowClose(b);
        rowOpen(b,sum+2);str(b,sum+2,0,"Не найденный адрес оставлен пустым; координаты в печатную таблицу не выводятся.",false);rowClose(b);
        return b.append("</sheetData></worksheet>").toString();
    }
}
