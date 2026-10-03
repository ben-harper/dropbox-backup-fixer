package com.example.dropboxbackupfixer.data.export

import android.content.Context
import com.example.dropboxbackupfixer.data.local.FolderSummary
import com.example.dropboxbackupfixer.data.local.MediaFileEntity
import com.example.dropboxbackupfixer.data.local.TypeSummary
import com.example.dropboxbackupfixer.data.local.YearSummary
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

class ExcelReportGenerator {

    fun generateReport(
        context: Context,
        files: List<MediaFileEntity>,
        summaryByType: List<TypeSummary>,
        summaryByYear: List<YearSummary>,
        summaryByFolder: List<FolderSummary>
    ): File {
        val workbook = XSSFWorkbook()

        val headerStyle = workbook.createCellStyle().apply {
            val font = workbook.createFont().apply { bold = true }
            setFont(font)
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        // Sheet 1: Summary
        val summarySheet = workbook.createSheet("Summary")
        var rowNum = 0

        val titleRow = summarySheet.createRow(rowNum++)
        titleRow.createCell(0).setCellValue("Dropbox Backup Fixer — Photo Inventory Report")
        titleRow.getCell(0).cellStyle = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply { bold = true; fontHeightInPoints = 14 })
        }

        val dateRow = summarySheet.createRow(rowNum++)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        dateRow.createCell(0).setCellValue("Generated Date: ${dateFormat.format(Date())}")
        rowNum++

        // Section: By File Type
        summarySheet.createRow(rowNum++).createCell(0).setCellValue("By File Type").apply {
            summarySheet.getRow(rowNum - 1).getCell(0).cellStyle = workbook.createCellStyle().apply { setFont(workbook.createFont().apply { bold = true }) }
        }
        val typeHeader = summarySheet.createRow(rowNum++)
        typeHeader.createCell(0).setCellValue("Extension")
        typeHeader.createCell(1).setCellValue("Count")
        typeHeader.createCell(2).setCellValue("Total Size")
        for (i in 0..2) typeHeader.getCell(i).cellStyle = headerStyle

        for (item in summaryByType) {
            val row = summarySheet.createRow(rowNum++)
            row.createCell(0).setCellValue(item.extension)
            row.createCell(1).setCellValue(item.count.toDouble())
            row.createCell(2).setCellValue(formatSize(item.totalSizeBytes))
        }
        rowNum++

        // Section: By Year
        summarySheet.createRow(rowNum++).createCell(0).setCellValue("By Year").apply {
            summarySheet.getRow(rowNum - 1).getCell(0).cellStyle = workbook.createCellStyle().apply { setFont(workbook.createFont().apply { bold = true }) }
        }
        val yearHeader = summarySheet.createRow(rowNum++)
        yearHeader.createCell(0).setCellValue("Year")
        yearHeader.createCell(1).setCellValue("Count")
        yearHeader.createCell(2).setCellValue("Total Size")
        for (i in 0..2) yearHeader.getCell(i).cellStyle = headerStyle

        for (item in summaryByYear) {
            val row = summarySheet.createRow(rowNum++)
            row.createCell(0).setCellValue(if (item.year == 0) "Unknown" else item.year.toString())
            row.createCell(1).setCellValue(item.count.toDouble())
            row.createCell(2).setCellValue(formatSize(item.totalSizeBytes))
        }
        rowNum++

        // Section: By Folder
        summarySheet.createRow(rowNum++).createCell(0).setCellValue("By Folder").apply {
            summarySheet.getRow(rowNum - 1).getCell(0).cellStyle = workbook.createCellStyle().apply { setFont(workbook.createFont().apply { bold = true }) }
        }
        val folderHeader = summarySheet.createRow(rowNum++)
        folderHeader.createCell(0).setCellValue("Folder")
        folderHeader.createCell(1).setCellValue("Count")
        folderHeader.createCell(2).setCellValue("Total Size")
        for (i in 0..2) folderHeader.getCell(i).cellStyle = headerStyle

        for (item in summaryByFolder) {
            val row = summarySheet.createRow(rowNum++)
            row.createCell(0).setCellValue(item.folder)
            row.createCell(1).setCellValue(item.count.toDouble())
            row.createCell(2).setCellValue(formatSize(item.totalSizeBytes))
        }
        rowNum++

        // Section: Backup Status
        summarySheet.createRow(rowNum++).createCell(0).setCellValue("Backup Status").apply {
            summarySheet.getRow(rowNum - 1).getCell(0).cellStyle = workbook.createCellStyle().apply { setFont(workbook.createFont().apply { bold = true }) }
        }
        val statusHeader = summarySheet.createRow(rowNum++)
        statusHeader.createCell(0).setCellValue("Status")
        statusHeader.createCell(1).setCellValue("Count")
        statusHeader.createCell(2).setCellValue("Total Size")
        for (i in 0..2) statusHeader.getCell(i).cellStyle = headerStyle

        val totalSize = files.sumOf { it.fileSizeBytes }
        val statusRow = summarySheet.createRow(rowNum++)
        statusRow.createCell(0).setCellValue("Not yet checked")
        statusRow.createCell(1).setCellValue(files.size.toDouble())
        statusRow.createCell(2).setCellValue(formatSize(totalSize))

        for (i in 0..2) summarySheet.setColumnWidth(i, 20 * 256)


        // Sheet 2: File Details
        val detailsSheet = workbook.createSheet("File Details")
        val detailsHeader = detailsSheet.createRow(0)
        val headers = arrayOf(
            "#", "File Name", "Path", "Type", "Size", "Size (bytes)", 
            "Date Taken", "Year", "Folder", "Content Hash", "Backup Status", "Dropbox Path"
        )
        for ((index, header) in headers.withIndex()) {
            val cell = detailsHeader.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerStyle
        }

        var detailRowNum = 1
        for ((index, file) in files.withIndex()) {
            val row = detailsSheet.createRow(detailRowNum++)
            row.createCell(0).setCellValue((index + 1).toDouble())
            row.createCell(1).setCellValue(file.fileName)
            row.createCell(2).setCellValue(file.filePath)
            row.createCell(3).setCellValue(file.fileExtension)
            row.createCell(4).setCellValue(formatSize(file.fileSizeBytes))
            row.createCell(5).setCellValue(file.fileSizeBytes.toDouble())
            
            val dateStr = file.dateTaken?.let { dateFormat.format(Date(it)) } ?: ""
            row.createCell(6).setCellValue(dateStr)
            
            row.createCell(7).setCellValue(file.year?.toString() ?: "Unknown")
            row.createCell(8).setCellValue(file.folder)
            row.createCell(9).setCellValue(file.contentHash ?: "")
            row.createCell(10).setCellValue(file.backupStatus)
            row.createCell(11).setCellValue(file.dropboxPath ?: "")
        }

        for (i in headers.indices) {
            detailsSheet.setColumnWidth(i, 20 * 256)
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val fileName = "backup_report_$timestamp.xlsx"
        val outFile = File(context.getExternalFilesDir(null), fileName)
        
        FileOutputStream(outFile).use { fos ->
            workbook.write(fos)
        }
        workbook.close()
        
        return outFile
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
        return String.format(Locale.US, "%.2f %s", bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }
}
